package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.menuhold.service.MenuHoldSnapshotQueryService;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyAudit;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.core.service.StoreScheduledActivationDecision;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReservationTimePolicyActivationServiceTest {

    private static final long STORE_ID = 7L;
    private static final long POLICY_ID = 22L;
    private static final Instant NOW = Instant.parse("2026-08-04T03:00:00Z");

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

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                storeScheduleService,
                storeServiceIntervalValidationService,
                policyRepository,
                storeService,
                idempotencyExecutor,
                auditRepository,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                capacityBucketRepository,
                reservationRepository,
                consumerAccountService,
                menuHoldSnapshotQueryService
        );
    }

    @Test
    void dueActivationLocksStoreThenTargetAndReplacesCurrentActive() {
        ReservationTimePolicyVersion active = activePolicy();
        ReservationTimePolicyVersion scheduled = scheduledPolicy();
        given(policyRepository.findStoreIdById(POLICY_ID))
                .willReturn(Optional.of(STORE_ID));
        given(storeService.inspectScheduledActivation(STORE_ID))
                .willReturn(new StoreScheduledActivationDecision(
                        STORE_ID,
                        "Asia/Seoul",
                        true
                ));
        given(policyRepository.findByIdForUpdate(POLICY_ID))
                .willReturn(Optional.of(scheduled));
        given(policyRepository.findByStoreIdAndStatusForUpdate(
                STORE_ID,
                ReservationTimePolicyStatus.ACTIVE
        )).willReturn(Optional.of(active));

        assertThat(reservationService.activateDueTimePolicy(POLICY_ID)).isTrue();

        assertThat(active.getStatus()).isEqualTo(ReservationTimePolicyStatus.RETIRED);
        assertThat(scheduled.getStatus()).isEqualTo(ReservationTimePolicyStatus.ACTIVE);
        assertThat(scheduled.getActivatedAt()).isEqualTo(NOW);
        then(policyRepository).should().flush();

        ArgumentCaptor<ReservationTimePolicyAudit> auditCaptor =
                ArgumentCaptor.forClass(ReservationTimePolicyAudit.class);
        then(auditRepository).should().save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getActorType())
                .isEqualTo(ReservationTimePolicyAudit.ActorType.SYSTEM);
        assertThat(auditCaptor.getValue().getOutcome())
                .isEqualTo(ReservationTimePolicyAudit.Outcome.SUCCEEDED);
        assertThat(auditCaptor.getValue().getConflictCheckStatus())
                .isEqualTo(ReservationTimePolicyAudit.ConflictCheckStatus.NOT_EVALUATED);
        assertThat(auditCaptor.getValue().getConflictCount()).isNull();
        assertThat(auditCaptor.getValue().getChangeReason()).isEqualTo("새 정책");
    }

    @Test
    void deterministicStoreIneligibilityMarksActivationFailedWithoutRetiringActive() {
        ReservationTimePolicyVersion active = activePolicy();
        ReservationTimePolicyVersion scheduled = scheduledPolicy();
        given(policyRepository.findStoreIdById(POLICY_ID))
                .willReturn(Optional.of(STORE_ID));
        given(storeService.inspectScheduledActivation(STORE_ID))
                .willReturn(new StoreScheduledActivationDecision(
                        STORE_ID,
                        "Asia/Seoul",
                        false
                ));
        given(policyRepository.findByIdForUpdate(POLICY_ID))
                .willReturn(Optional.of(scheduled));
        given(policyRepository.findByStoreIdAndStatusForUpdate(
                STORE_ID,
                ReservationTimePolicyStatus.ACTIVE
        )).willReturn(Optional.of(active));

        assertThat(reservationService.activateDueTimePolicy(POLICY_ID)).isFalse();

        assertThat(active.getStatus()).isEqualTo(ReservationTimePolicyStatus.ACTIVE);
        assertThat(scheduled.getStatus())
                .isEqualTo(ReservationTimePolicyStatus.ACTIVATION_FAILED);
        then(policyRepository).should(org.mockito.Mockito.never()).flush();

        ArgumentCaptor<ReservationTimePolicyAudit> auditCaptor =
                ArgumentCaptor.forClass(ReservationTimePolicyAudit.class);
        then(auditRepository).should().save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOutcome())
                .isEqualTo(ReservationTimePolicyAudit.Outcome.ACTIVATION_FAILED);
        assertThat(auditCaptor.getValue().getChangeReason()).isEqualTo("새 정책");
    }

    @Test
    void missingOrNoLongerScheduledCandidateIsAnIdempotentNoOp() {
        given(policyRepository.findStoreIdById(POLICY_ID))
                .willReturn(Optional.empty());

        assertThat(reservationService.activateDueTimePolicy(POLICY_ID)).isFalse();

        then(storeService).shouldHaveNoInteractions();
        then(auditRepository).shouldHaveNoInteractions();
    }

    private ReservationTimePolicyVersion activePolicy() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                STORE_ID,
                1L,
                30,
                60,
                15
        );
        policy.activate(NOW.minusSeconds(3600), "기존 정책");
        return policy;
    }

    private ReservationTimePolicyVersion scheduledPolicy() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                STORE_ID,
                2L,
                30,
                90,
                15
        );
        policy.schedule(NOW, NOW.minusSeconds(3600), "새 정책");
        return policy;
    }
}
