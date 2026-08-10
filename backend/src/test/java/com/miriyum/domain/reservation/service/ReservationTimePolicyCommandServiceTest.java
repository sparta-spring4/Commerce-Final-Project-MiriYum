package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.menuhold.service.MenuHoldSnapshotQueryService;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyDraftRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationRequest.PublicationMode;
import com.miriyum.domain.reservation.dto.response.ReservationTimePolicyResponse;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyAudit;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReservationTimePolicyCommandServiceTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-04T03:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000"
    );

    @Mock
    private StoreScheduleService storeScheduleService;

    @Mock
    private StoreServiceIntervalValidationService storeServiceIntervalValidationService;

    @Mock
    private ReservationTimePolicyVersionRepository policyRepository;

    @Mock
    private StoreService storeService;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    @Mock
    private ReservationTimePolicyAuditRepository auditRepository;

    @Mock
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ConsumerAccountService consumerAccountService;

    @Mock
    private MenuHoldSnapshotQueryService menuHoldSnapshotQueryService;

    private ObjectMapper objectMapper;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        reservationService = new ReservationService(
                storeScheduleService,
                storeServiceIntervalValidationService,
                policyRepository,
                storeService,
                idempotencyExecutor,
                auditRepository,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                capacityBucketRepository,
                reservationRepository,
                consumerAccountService,
                menuHoldSnapshotQueryService
        );
        given(idempotencyExecutor.execute(any(), any()))
                .willAnswer(invocation -> executeWork(invocation.getArgument(1)));
    }

    @Test
    void createDraftClaimsIdempotencyThenLocksStoreAndAllocatesNextVersion() {
        given(policyRepository.findMaxVersionNumberByStoreId(STORE_ID))
                .willReturn(2L);
        given(policyRepository.saveAndFlush(any(ReservationTimePolicyVersion.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> result =
                reservationService.createTimePolicyDraft(
                        OPERATOR_ID,
                        STORE_ID,
                        KEY,
                        new ReservationTimePolicyDraftRequest(30, 90, 15)
                );

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data()).satisfies(response -> {
            assertThat(response.storeId()).isEqualTo("7");
            assertThat(response.version()).isEqualTo(3L);
            assertThat(response.status()).isEqualTo(ReservationTimePolicyStatus.DRAFT);
        });
        InOrder order = Mockito.inOrder(
                idempotencyExecutor,
                storeService,
                policyRepository
        );
        order.verify(storeService).requireManagementOwnership(
                OPERATOR_ID,
                STORE_ID
        );
        order.verify(idempotencyExecutor).execute(any(), any());
        order.verify(storeService).requireSchedulePublicationAuthority(
                OPERATOR_ID,
                STORE_ID
        );
        order.verify(policyRepository).findMaxVersionNumberByStoreId(STORE_ID);

        ArgumentCaptor<IdempotencyCommand> commandCaptor =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(idempotencyExecutor).should().execute(commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue().principalNamespace())
                .isEqualTo("store-operator");
        assertThat(commandCaptor.getValue().commandType())
                .isEqualTo("RESERVATION_TIME_POLICY_DRAFT_CREATE");
    }

    @Test
    void idempotentReplayStillChecksCurrentStoreOwnership() {
        ReservationTimePolicyResponse replayed = ReservationTimePolicyResponse.from(
                draftPolicy(1L)
        );
        Mockito.reset(idempotencyExecutor);
        org.mockito.BDDMockito.willReturn(new IdempotentOutcome(
                        true,
                        200,
                        "SUCCESS",
                        "RESERVATION_TIME_POLICY",
                        "7:1",
                        objectMapper.readTree(objectMapper.writeValueAsString(replayed))
                ))
                .given(idempotencyExecutor)
                .execute(any(), any());

        ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> result =
                reservationService.createTimePolicyDraft(
                        OPERATOR_ID,
                        STORE_ID,
                        KEY,
                        new ReservationTimePolicyDraftRequest(30, 90, 15)
                );

        assertThat(result.data()).isEqualTo(replayed);
        then(storeService).should().requireManagementOwnership(
                OPERATOR_ID,
                STORE_ID
        );
        then(storeService).should(never()).requireSchedulePublicationAuthority(
                OPERATOR_ID,
                STORE_ID
        );
    }

    @Test
    void immediatePublicationRetiresCurrentActiveAndActivatesTargetAtomically() {
        ReservationTimePolicyVersion active = activePolicy(1L);
        ReservationTimePolicyVersion target = draftPolicy(2L);
        given(policyRepository.findByStoreIdAndVersionNumberForUpdate(STORE_ID, 2L))
                .willReturn(Optional.of(target));
        given(policyRepository.findByStoreIdAndStatusForUpdate(
                STORE_ID,
                ReservationTimePolicyStatus.ACTIVE
        )).willReturn(Optional.of(active));

        ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> result =
                reservationService.publishTimePolicy(
                        OPERATOR_ID,
                        STORE_ID,
                        2L,
                        KEY,
                        new ReservationTimePolicyPublicationRequest(
                                PublicationMode.IMMEDIATE,
                                null,
                                "저녁 운영 확대"
                        )
                );

        assertThat(active.getStatus()).isEqualTo(ReservationTimePolicyStatus.RETIRED);
        assertThat(target.getStatus()).isEqualTo(ReservationTimePolicyStatus.ACTIVE);
        assertThat(target.getEffectiveAt()).isEqualTo(NOW);
        assertThat(target.getChangeReason()).isEqualTo("저녁 운영 확대");
        assertThat(result.data().status()).isEqualTo(ReservationTimePolicyStatus.ACTIVE);
        then(storeService).should().requireManagementOwnership(OPERATOR_ID, STORE_ID);
        then(policyRepository).should().flush();
    }

    @Test
    void scheduledPublicationKeepsCurrentActiveUntilDueActivation() {
        ReservationTimePolicyVersion active = activePolicy(1L);
        ReservationTimePolicyVersion target = draftPolicy(2L);
        Instant effectiveAt = NOW.plusSeconds(3600);
        given(policyRepository.findByStoreIdAndVersionNumberForUpdate(STORE_ID, 2L))
                .willReturn(Optional.of(target));
        given(policyRepository.findByStoreIdAndStatusForUpdate(
                STORE_ID,
                ReservationTimePolicyStatus.ACTIVE
        )).willReturn(Optional.of(active));

        reservationService.publishTimePolicy(
                OPERATOR_ID,
                STORE_ID,
                2L,
                KEY,
                new ReservationTimePolicyPublicationRequest(
                        PublicationMode.SCHEDULED,
                        OffsetDateTime.ofInstant(effectiveAt, ZoneOffset.UTC),
                        "다음 주부터 적용"
                )
        );

        assertThat(active.getStatus()).isEqualTo(ReservationTimePolicyStatus.ACTIVE);
        assertThat(target.getStatus()).isEqualTo(ReservationTimePolicyStatus.SCHEDULED);
        assertThat(target.getEffectiveAt()).isEqualTo(effectiveAt);
        assertThat(target.getChangeReason()).isEqualTo("다음 주부터 적용");
        then(storeService).should().requireManagementOwnership(OPERATOR_ID, STORE_ID);
    }

    @Test
    void scheduledPublicationCanBeCancelledOnlyBeforeEffectiveBoundary() {
        ReservationTimePolicyVersion active = activePolicy(1L);
        ReservationTimePolicyVersion target = draftPolicy(2L);
        target.schedule(NOW.plusSeconds(3600), NOW, "다음 주부터 적용");
        given(policyRepository.findByStoreIdAndVersionNumberForUpdate(STORE_ID, 2L))
                .willReturn(Optional.of(target));
        given(policyRepository.findByStoreIdAndStatusForUpdate(
                STORE_ID,
                ReservationTimePolicyStatus.ACTIVE
        )).willReturn(Optional.of(active));

        ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> result =
                reservationService.cancelTimePolicyPublication(
                        OPERATOR_ID,
                        STORE_ID,
                        2L,
                        KEY,
                        new ReservationTimePolicyPublicationCancellationRequest("적용 보류")
                );

        assertThat(target.getStatus()).isEqualTo(ReservationTimePolicyStatus.DRAFT);
        assertThat(target.getEffectiveAt()).isNull();
        assertThat(target.getChangeReason()).isEqualTo("다음 주부터 적용");
        assertThat(result.data().status()).isEqualTo(ReservationTimePolicyStatus.DRAFT);
        then(storeService).should().requireManagementOwnership(OPERATOR_ID, STORE_ID);
    }

    @Test
    void missingOrCrossStorePolicyVersionUsesReservation010() {
        given(policyRepository.findByStoreIdAndVersionNumberForUpdate(STORE_ID, 99L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.publishTimePolicy(
                OPERATOR_ID,
                STORE_ID,
                99L,
                KEY,
                new ReservationTimePolicyPublicationRequest(
                        PublicationMode.IMMEDIATE,
                        null,
                        "즉시 적용"
                )
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.TIME_POLICY_CONFLICT));
    }

    private IdempotentOutcome executeWork(
            Supplier<BusinessResult<Object>> businessWork
    ) {
        BusinessResult<Object> result = businessWork.get();
        String payload = objectMapper.writeValueAsString(result.data());
        return new IdempotentOutcome(
                false,
                result.httpStatus(),
                result.responseCode(),
                result.resourceType(),
                result.resourceId(),
                objectMapper.readTree(payload)
        );
    }

    private ReservationTimePolicyVersion draftPolicy(long version) {
        return ReservationTimePolicyVersion.createDraft(
                STORE_ID,
                version,
                30,
                90,
                15
        );
    }

    private ReservationTimePolicyVersion activePolicy(long version) {
        ReservationTimePolicyVersion policy = draftPolicy(version);
        policy.activate(NOW.minusSeconds(60), "기존 정책");
        return policy;
    }
}
