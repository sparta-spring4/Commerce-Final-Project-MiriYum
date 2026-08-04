package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ReservationTimePolicyActivationJobTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(
                            ReservationTimePolicyVersionRepository.class,
                            () -> mock(ReservationTimePolicyVersionRepository.class)
                    )
                    .withBean(
                            ReservationService.class,
                            () -> mock(ReservationService.class)
                    )
                    .withBean(Clock.class, Clock::systemUTC)
                    .withUserConfiguration(ReservationTimePolicyActivationJob.class);

    @Mock
    private ReservationTimePolicyVersionRepository policyRepository;

    @Mock
    private ReservationService reservationService;

    @Test
    void disabledPropertyDoesNotRegisterActivationJob() {
        contextRunner
                .withPropertyValues(
                        "miriyum.reservation.time-policy.activation-enabled=false"
                )
                .run(context ->
                        assertThat(context)
                                .doesNotHaveBean(ReservationTimePolicyActivationJob.class));
    }

    @Test
    void dueCandidateIdsAreActivatedInRepositoryOrder() {
        Instant now = Instant.parse("2026-08-04T03:00:00Z");
        given(policyRepository.findDueScheduledIds(
                ReservationTimePolicyStatus.SCHEDULED,
                now,
                PageRequest.of(0, 100)
        )).willReturn(List.of(11L, 12L));
        ReservationTimePolicyActivationJob job = new ReservationTimePolicyActivationJob(
                policyRepository,
                reservationService,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        job.activateDuePolicies();

        InOrder order = Mockito.inOrder(reservationService);
        order.verify(reservationService).activateDueTimePolicy(11L);
        order.verify(reservationService).activateDueTimePolicy(12L);
    }

    @Test
    void oneTransientCandidateFailureDoesNotBlockRemainingCandidates() {
        Instant now = Instant.parse("2026-08-04T03:00:00Z");
        given(policyRepository.findDueScheduledIds(
                ReservationTimePolicyStatus.SCHEDULED,
                now,
                PageRequest.of(0, 100)
        )).willReturn(List.of(11L, 12L));
        willThrow(new IllegalStateException("temporary"))
                .given(reservationService).activateDueTimePolicy(11L);
        ReservationTimePolicyActivationJob job = new ReservationTimePolicyActivationJob(
                policyRepository,
                reservationService,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        job.activateDuePolicies();

        then(reservationService).should().activateDueTimePolicy(12L);
    }
}
