package com.miriyum.domain.store.closure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.store.closure.dto.RegularClosureDraftRequest;
import com.miriyum.domain.store.closure.dto.RegularClosureResponse;
import com.miriyum.domain.store.closure.entity.RegularClosureVersion;
import com.miriyum.domain.store.closure.entity.StoreClosureAuditEvent;
import com.miriyum.domain.store.closure.entity.TemporaryClosure;
import com.miriyum.domain.store.closure.model.TemporaryClosureReason;
import com.miriyum.domain.store.closure.model.StoreClosureActorType;
import com.miriyum.domain.store.closure.repository.RegularClosureVersionRepository;
import com.miriyum.domain.store.closure.repository.StoreClosureAuditEventRepository;
import com.miriyum.domain.store.closure.repository.TemporaryClosureRepository;
import com.miriyum.domain.store.service.StoreScheduleAuthority;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.service.StoreScheduledActivationDecision;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationCancellationRequest;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationRequest;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.model.ConflictCheckStatus;
import com.miriyum.domain.store.schedule.model.PublicationMode;
import com.miriyum.domain.store.schedule.model.ScheduleAuditOutcome;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.schedule.service.ScheduleCommandResult;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StoreClosureServiceTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 7L;
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-03T03:00:00Z"), ZoneOffset.UTC);

    @Mock private StoreService storeService;
    @Mock private StoreScheduleStateRepository stateRepository;
    @Mock private RegularClosureVersionRepository regularRepository;
    @Mock private TemporaryClosureRepository temporaryRepository;
    @Mock private StoreClosureAuditEventRepository auditRepository;
    @Mock private IdempotencyExecutor idempotencyExecutor;

    private ObjectMapper objectMapper;
    private StoreClosureService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new StoreClosureService(
                storeService,
                stateRepository,
                regularRepository,
                temporaryRepository,
                auditRepository,
                idempotencyExecutor,
                objectMapper,
                CLOCK);
    }

    @Test
    void draftAllocatesVersionWithoutChangingActivePointer() {
        StoreScheduleState state = StoreScheduleState.initialize(STORE_ID);
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(regularRepository.saveAndFlush(any())).willAnswer(invocation -> {
            RegularClosureVersion version = invocation.getArgument(0);
            ReflectionTestUtils.setField(version, "id", 41L);
            return version;
        });
        executeBusinessWork();

        ScheduleCommandResult<RegularClosureResponse> result = service.createRegularDraft(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(KEY),
                new RegularClosureDraftRequest(
                        List.of(DayOfWeek.MONDAY), List.of()));

        assertThat(result.data().version()).isEqualTo(1L);
        assertThat(result.data().status()).isEqualTo(ScheduleVersionStatus.DRAFT);
        assertThat(state.getActiveRegularClosureVersionId()).isNull();
        StoreClosureAuditEvent event = capturedAuditEvent();
        assertThat(event.getAction()).isEqualTo("DRAFT_CREATED");
        assertAuditMetadata(event, null, null, CLOCK.instant(), null,
                ScheduleAuditOutcome.SUCCEEDED);
        assertNotEvaluated(event);
    }

    @Test
    void immediatePublicationActivatesDraftAndRetiresPreviousVersion() {
        StoreScheduleState state = StoreScheduleState.initialize(STORE_ID);
        RegularClosureVersion previous = version(1L, 41L);
        previous.activate(CLOCK.instant().minusSeconds(60), "기존 게시");
        RegularClosureVersion target = version(2L, 42L);
        state.activateRegularClosure(41L);
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(regularRepository.findByStoreIdAndVersionNumber(STORE_ID, 2L))
                .willReturn(Optional.of(target));
        given(regularRepository.findById(41L)).willReturn(Optional.of(previous));
        executeBusinessWork();

        ScheduleCommandResult<RegularClosureResponse> result = service.publishRegular(
                OPERATOR_ID,
                STORE_ID,
                2L,
                IdempotencyKey.parse(KEY),
                new SchedulePublicationRequest(
                        PublicationMode.IMMEDIATE, null, "새 휴무 게시"));

        assertThat(result.data().status()).isEqualTo(ScheduleVersionStatus.ACTIVE);
        assertThat(previous.getStatus()).isEqualTo(ScheduleVersionStatus.RETIRED);
        assertThat(state.getActiveRegularClosureVersionId()).isEqualTo(42L);
        StoreClosureAuditEvent event = capturedAuditEvent();
        assertThat(event.getAction()).isEqualTo("ACTIVATED");
        assertAuditMetadata(event, 1L, 2L, CLOCK.instant(), CLOCK.instant(),
                ScheduleAuditOutcome.SUCCEEDED);
        assertNotEvaluated(event);
    }

    @Test
    void scheduledPublicationAuditPreservesUnknownConflictCount() {
        StoreScheduleState state = StoreScheduleState.initialize(STORE_ID);
        RegularClosureVersion previous = version(1L, 41L);
        previous.activate(CLOCK.instant().minusSeconds(60), "기존 게시");
        state.activateRegularClosure(41L);
        RegularClosureVersion target = version(2L, 42L);
        Instant effectiveAt = CLOCK.instant().plusSeconds(3600);
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID)).willReturn(Optional.of(state));
        given(regularRepository.findByStoreIdAndVersionNumber(STORE_ID, 2L))
                .willReturn(Optional.of(target));
        given(regularRepository.findById(41L)).willReturn(Optional.of(previous));
        given(temporaryRepository.findNonCancelledEndingAfter(STORE_ID, effectiveAt))
                .willReturn(List.of());
        executeBusinessWork();

        service.publishRegular(
                OPERATOR_ID,
                STORE_ID,
                2L,
                IdempotencyKey.parse(KEY),
                new SchedulePublicationRequest(
                        PublicationMode.SCHEDULED,
                        OffsetDateTime.ofInstant(effectiveAt, ZoneOffset.UTC),
                        "예약 게시"));

        StoreClosureAuditEvent event = capturedAuditEvent();
        assertThat(event.getAction()).isEqualTo("PUBLICATION_SCHEDULED");
        assertAuditMetadata(event, 1L, 1L, CLOCK.instant(), effectiveAt,
                ScheduleAuditOutcome.SUCCEEDED);
        assertNotEvaluated(event);
    }

    @Test
    void publicationCancellationAuditPreservesUnknownConflictCount() {
        StoreScheduleState state = StoreScheduleState.initialize(STORE_ID);
        RegularClosureVersion previous = version(1L, 41L);
        previous.activate(CLOCK.instant().minusSeconds(60), "기존 게시");
        state.activateRegularClosure(41L);
        RegularClosureVersion target = version(2L, 42L);
        target.schedule(CLOCK.instant().plusSeconds(3600), "예약 게시");
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID)).willReturn(Optional.of(state));
        given(regularRepository.findByStoreIdAndVersionNumber(STORE_ID, 2L))
                .willReturn(Optional.of(target));
        given(regularRepository.findById(41L)).willReturn(Optional.of(previous));
        executeBusinessWork();

        service.cancelRegularPublication(
                OPERATOR_ID,
                STORE_ID,
                2L,
                IdempotencyKey.parse(KEY),
                new SchedulePublicationCancellationRequest("예약 취소"));

        StoreClosureAuditEvent event = capturedAuditEvent();
        assertThat(event.getAction()).isEqualTo("PUBLICATION_CANCELLED");
        assertAuditMetadata(event, 1L, 1L, CLOCK.instant(),
                CLOCK.instant().plusSeconds(3600), ScheduleAuditOutcome.SUCCEEDED);
        assertNotEvaluated(event);
    }

    @Test
    void immediatePublicationRejectsRegularRuleConflictingWithTemporaryClosure() {
        StoreScheduleState state = StoreScheduleState.initialize(STORE_ID);
        RegularClosureVersion target = RegularClosureVersion.createDraft(
                STORE_ID, 1L, "Asia/Seoul", List.of(DayOfWeek.MONDAY), List.of());
        ReflectionTestUtils.setField(target, "id", 42L);
        TemporaryClosure temporary = TemporaryClosure.create(
                STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                "Asia/Seoul", TemporaryClosureReason.OTHER, null);
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID)).willReturn(Optional.of(state));
        given(regularRepository.findByStoreIdAndVersionNumber(STORE_ID, 1L)).willReturn(Optional.of(target));
        given(temporaryRepository.findNonCancelledEndingAfter(STORE_ID, CLOCK.instant()))
                .willReturn(List.of(temporary));
        executeBusinessWork();

        assertThatThrownBy(() -> service.publishRegular(OPERATOR_ID, STORE_ID, 1L,
                IdempotencyKey.parse(KEY),
                new SchedulePublicationRequest(PublicationMode.IMMEDIATE, null, "게시")))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class);
    }

    @Test
    void scheduledPublicationRejectsDuplicateEffectiveAt() {
        StoreScheduleState state = StoreScheduleState.initialize(STORE_ID);
        RegularClosureVersion target = version(2L, 42L);
        Instant effectiveAt = CLOCK.instant().plusSeconds(3600);
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID)).willReturn(Optional.of(state));
        given(regularRepository.findByStoreIdAndVersionNumber(STORE_ID, 2L))
                .willReturn(Optional.of(target));
        given(regularRepository.existsByStoreIdAndEffectiveAt(STORE_ID, effectiveAt))
                .willReturn(true);
        executeBusinessWork();

        assertThatThrownBy(() -> service.publishRegular(
                OPERATOR_ID,
                STORE_ID,
                2L,
                IdempotencyKey.parse(KEY),
                new SchedulePublicationRequest(
                        PublicationMode.SCHEDULED,
                        OffsetDateTime.ofInstant(effectiveAt, ZoneOffset.UTC),
                        "예약 게시")))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class);
        assertThat(target.getStatus()).isEqualTo(ScheduleVersionStatus.DRAFT);
    }

    @Test
    void scheduledActivationIsAuditedAsSystemActor() {
        StoreScheduleState state = StoreScheduleState.initialize(STORE_ID);
        RegularClosureVersion previous = version(1L, 41L);
        previous.activate(CLOCK.instant().minusSeconds(60), "기존 게시");
        state.activateRegularClosure(41L);
        RegularClosureVersion scheduled = version(2L, 42L);
        Instant effectiveAt = CLOCK.instant().minusSeconds(30);
        scheduled.schedule(effectiveAt, "예약 게시");
        given(regularRepository.findStoreIdById(42L)).willReturn(Optional.of(STORE_ID));
        given(storeService.inspectScheduledActivation(STORE_ID))
                .willReturn(new StoreScheduledActivationDecision(STORE_ID, "Asia/Seoul", true));
        given(stateRepository.findForUpdateByStoreId(STORE_ID)).willReturn(Optional.of(state));
        given(regularRepository.findForUpdateById(42L)).willReturn(Optional.of(scheduled));
        given(regularRepository.findById(41L)).willReturn(Optional.of(previous));
        given(regularRepository
                .findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        STORE_ID, ScheduleVersionStatus.SCHEDULED, CLOCK.instant()))
                .willReturn(Optional.of(scheduled));

        service.activateDueRegular(42L);

        StoreClosureAuditEvent event = capturedAuditEvent();
        assertThat(event.getAction()).isEqualTo("ACTIVATED");
        assertThat(event.getActorType()).isEqualTo(StoreClosureActorType.SYSTEM);
        assertThat(event.getActorId()).isNull();
        assertAuditMetadata(event, 1L, 2L, effectiveAt, effectiveAt,
                ScheduleAuditOutcome.SUCCEEDED);
        assertNotEvaluated(event);
    }

    @Test
    void scheduledActivationFailureAuditPreservesUnknownConflictCount() {
        StoreScheduleState state = StoreScheduleState.initialize(STORE_ID);
        RegularClosureVersion previous = version(1L, 41L);
        previous.activate(CLOCK.instant().minusSeconds(60), "기존 게시");
        state.activateRegularClosure(41L);
        RegularClosureVersion scheduled = version(2L, 42L);
        Instant effectiveAt = CLOCK.instant().minusSeconds(30);
        scheduled.schedule(effectiveAt, "예약 게시");
        given(regularRepository.findStoreIdById(42L)).willReturn(Optional.of(STORE_ID));
        given(storeService.inspectScheduledActivation(STORE_ID))
                .willReturn(new StoreScheduledActivationDecision(STORE_ID, "Asia/Seoul", false));
        given(stateRepository.findForUpdateByStoreId(STORE_ID)).willReturn(Optional.of(state));
        given(regularRepository.findForUpdateById(42L)).willReturn(Optional.of(scheduled));
        given(regularRepository.findById(41L)).willReturn(Optional.of(previous));
        given(regularRepository
                .findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        STORE_ID, ScheduleVersionStatus.SCHEDULED, CLOCK.instant()))
                .willReturn(Optional.of(scheduled));

        service.activateDueRegular(42L);

        StoreClosureAuditEvent event = capturedAuditEvent();
        assertThat(event.getAction()).isEqualTo("ACTIVATION_FAILED");
        assertThat(event.getActorType()).isEqualTo(StoreClosureActorType.SYSTEM);
        assertThat(event.getActorId()).isNull();
        assertAuditMetadata(event, 1L, 1L, effectiveAt, effectiveAt,
                ScheduleAuditOutcome.FAILED);
        assertNotEvaluated(event);
    }

    private StoreClosureAuditEvent capturedAuditEvent() {
        ArgumentCaptor<StoreClosureAuditEvent> captor =
                ArgumentCaptor.forClass(StoreClosureAuditEvent.class);
        then(auditRepository).should().save(captor.capture());
        return captor.getValue();
    }

    private void assertNotEvaluated(StoreClosureAuditEvent event) {
        assertThat(event.getConflictCheckStatus()).isEqualTo(ConflictCheckStatus.NOT_EVALUATED);
        assertThat(event.getConflictCount()).isNull();
    }

    private void assertAuditMetadata(
            StoreClosureAuditEvent event,
            Long previousActiveVersion,
            Long newActiveVersion,
            Instant requestedAt,
            Instant effectiveAt,
            ScheduleAuditOutcome outcome
    ) {
        assertThat(event.getPreviousActiveVersion()).isEqualTo(previousActiveVersion);
        assertThat(event.getNewActiveVersion()).isEqualTo(newActiveVersion);
        assertThat(event.getRequestedAt()).isEqualTo(requestedAt);
        assertThat(event.getEffectiveAt()).isEqualTo(effectiveAt);
        assertThat(event.getOccurredAt()).isEqualTo(CLOCK.instant());
        assertThat(event.getOutcome()).isEqualTo(outcome);
    }

    private RegularClosureVersion version(long versionNumber, long id) {
        RegularClosureVersion version = RegularClosureVersion.createDraft(
                STORE_ID, versionNumber, "Asia/Seoul", List.of(), List.of());
        ReflectionTestUtils.setField(version, "id", id);
        return version;
    }

    private void executeBusinessWork() {
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(
                    false,
                    result.httpStatus(),
                    result.responseCode(),
                    result.resourceType(),
                    result.resourceId(),
                    objectMapper.valueToTree(result.data()));
        });
    }
}
