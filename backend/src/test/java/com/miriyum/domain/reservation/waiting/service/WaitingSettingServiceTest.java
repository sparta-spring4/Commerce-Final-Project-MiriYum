package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.*;
import com.miriyum.domain.reservation.waiting.entity.*;
import com.miriyum.domain.reservation.waiting.repository.*;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.*;
import java.time.*;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WaitingSettingServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440271");

    @Mock WaitingStoreAuthorityPort authority;
    @Mock WaitingSettingRepository settings;
    @Mock WaitingSettingAuditRepository audits;
    @Mock WaitingClosureService closures;
    @Mock IdempotencyExecutor idempotency;
    @Mock WaitingSettingTransactionExecutor transactions;
    ObjectMapper mapper;
    WaitingSettingService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        service = new WaitingSettingService(authority, settings, audits, closures,
                idempotency, transactions, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient().when(transactions.execute(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        org.mockito.Mockito.lenient().when(settings.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        replayBusinessWork();
    }

    @Test
    void missingSettingReturnsSafeDefaultsWithoutWriting() {
        given(settings.findByStoreId(22L)).willReturn(Optional.empty());

        WaitingSettingSnapshot result = service.get(33L, 22L);

        assertThat(result).isEqualTo(new WaitingSettingSnapshot(
                "22", false, WaitingReceptionMode.PAUSED, 60, 0L));
        then(authority).should().requireRead(33L, 22L);
        then(settings).should().findByStoreId(22L);
        then(settings).should(never()).saveAndFlush(any());
    }

    @Test
    void impactUsesOnlyTheClosurePublicContract() {
        given(settings.findByStoreId(22L)).willReturn(Optional.empty());
        given(closures.inspectActiveTeams(33L, 22L))
                .willReturn(new WaitingActiveTeamImpact(22L, 4L));

        assertThat(service.inspectDeactivation(33L, 22L))
                .isEqualTo(new WaitingSettingDeactivationImpact("22", 0L, 4L));
    }

    @Test
    void keepActiveCreatesVersionOneAndDoesNotStartAClosureJob() {
        given(settings.findByStoreId(22L)).willReturn(Optional.empty());
        given(closures.inspectActiveTeams(33L, 22L))
                .willReturn(new WaitingActiveTeamImpact(22L, 2L));

        WaitingSettingCommandResult result = service.replace(33L, 22L, KEY,
                request(0L, false, WaitingReceptionMode.PAUSED, 60,
                        WaitingDisableAction.KEEP_ACTIVE));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data()).isEqualTo(new WaitingSettingSnapshot(
                "22", false, WaitingReceptionMode.PAUSED, 60, 1L));
        then(closures).should().inspectActiveTeams(33L, 22L);
        then(closures).shouldHaveNoMoreInteractions();
    }

    @Test
    void closeActiveTeamsBindsTheNewVersionAndReturnsAcceptedJob() {
        given(settings.findByStoreId(22L)).willReturn(Optional.empty());
        given(closures.inspectActiveTeams(33L, 22L))
                .willReturn(new WaitingActiveTeamImpact(22L, 2L));
        WaitingClosureJobSnapshot job = new WaitingClosureJobSnapshot(
                "91", "22", WaitingClosureJobStatus.PENDING, 2, 0, 0, 0, NOW, null);
        given(closures.startClosure(33L, 22L, KEY, 1L))
                .willReturn(new WaitingClosureCommandResult(202, job));

        WaitingSettingCommandResult result = service.replace(33L, 22L, KEY,
                request(0L, false, WaitingReceptionMode.PAUSED, 60,
                        WaitingDisableAction.CLOSE_ACTIVE_TEAMS));

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.data()).isEqualTo(job);
        then(closures).should().startClosure(33L, 22L, KEY, 1L);
    }

    @Test
    void activeTeamsRequireAnExplicitDisableAction() {
        given(settings.findByStoreId(22L)).willReturn(Optional.empty());
        given(closures.inspectActiveTeams(33L, 22L))
                .willReturn(new WaitingActiveTeamImpact(22L, 1L));

        assertThatThrownBy(() -> service.replace(33L, 22L, KEY,
                request(0L, false, WaitingReceptionMode.PAUSED, 60, null)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_DISABLE_ACTION_REQUIRED);
        then(settings).should(never()).saveAndFlush(any());
    }

    @Test
    void staleExpectedVersionDoesNotMutateOrInspectTeams() {
        WaitingSetting current = WaitingSetting.create(
                22L, true, WaitingReceptionMode.AUTO, 60, NOW.minusSeconds(1));
        given(settings.findByStoreId(22L)).willReturn(Optional.of(current));

        assertThatThrownBy(() -> service.replace(33L, 22L, KEY,
                request(0L, false, WaitingReceptionMode.PAUSED, 60,
                        WaitingDisableAction.KEEP_ACTIVE)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_SETTING_VERSION_CONFLICT);
        then(closures).shouldHaveNoInteractions();
    }

    @Test
    void staleAutomaticJobCannotActOnANewerSettingVersion() {
        WaitingSetting current = WaitingSetting.create(
                22L, true, WaitingReceptionMode.AUTO, 60, NOW.minusSeconds(2));
        current.replace(1L, false, WaitingReceptionMode.PAUSED, 60, NOW.minusSeconds(1));
        given(settings.findByStoreId(22L)).willReturn(Optional.of(current));

        assertThat(service.openAutomatically(22L, 1L)).isFalse();
        assertThat(current.getVersion()).isEqualTo(2L);
        assertThat(current.isEnabled()).isFalse();
    }

    private static WaitingSettingUpdateRequest request(long version, boolean enabled,
            WaitingReceptionMode mode, int minutes, WaitingDisableAction action) {
        return new WaitingSettingUpdateRequest(version, enabled, mode, minutes, action);
    }

    private void replayBusinessWork() {
        org.mockito.Mockito.lenient().when(idempotency.execute(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<BusinessResult<Object>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(), mapper.valueToTree(result.data()));
        });
    }
}
