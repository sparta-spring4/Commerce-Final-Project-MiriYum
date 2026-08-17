package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.config.ReservationDepositProcessConfig;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;

class ReservationDepositProcessJobTest {

    @Test
    void conditionalScheduledWorkerUsesDedicatedSchedulerAndStableLeaseOwner()
            throws Exception {
        ReservationDepositProcessService processService =
                mock(ReservationDepositProcessService.class);
        ReservationDepositProcessCommandFacade commandFacade =
                mock(ReservationDepositProcessCommandFacade.class);
        given(processService.claimDue(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(100))).willReturn(List.of());
        ReservationDepositProcessJob job = new ReservationDepositProcessJob(
                processService,
                commandFacade);
        ReservationDepositProcessConfig.ProcessScheduledWorker worker =
                new ReservationDepositProcessConfig.ProcessScheduledWorker(job);
        Method scheduledMethod = ReservationDepositProcessConfig.ProcessScheduledWorker.class
                .getMethod("runScheduled");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);

        scheduledMethod.invoke(worker);
        scheduledMethod.invoke(worker);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo("reservationDepositProcessScheduler");
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("#{@reservationDepositProcessPollDelayMs}");
        ArgumentCaptor<String> owners = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(processService, times(2))
                .claimDue(owners.capture(), org.mockito.ArgumentMatchers.eq(100));
        String owner = owners.getAllValues().getFirst();
        assertThat(owners.getAllValues()).hasSize(2).allMatch(owner::equals);
        assertThat(owner).startsWith("reservation-deposit-process-");
        assertThat(ReservationDepositProcessJob.class
                .getMethod("runScheduled")
                .getAnnotation(Scheduled.class)).isNull();
        assertSchedulerBean("reservationDepositProcessScheduler");
    }

    @Test
    void claimsBeforeCommandsAndContinuesAfterAnIndividualFailure() {
        ReservationDepositProcessService processService =
                mock(ReservationDepositProcessService.class);
        ReservationDepositProcessCommandFacade commandFacade =
                mock(ReservationDepositProcessCommandFacade.class);
        ReservationDepositProcessService.Claim first =
                new ReservationDepositProcessService.Claim(91L, "worker-a", 1L);
        ReservationDepositProcessService.Claim second =
                new ReservationDepositProcessService.Claim(92L, "worker-a", 1L);
        given(processService.claimDue("worker-a", 10))
                .willReturn(List.of(first, second));
        given(commandFacade.reconcileClaimed(first))
                .willThrow(new IllegalStateException("individual failure"));
        given(commandFacade.reconcileClaimed(second)).willReturn(true);
        ReservationDepositProcessJob job = new ReservationDepositProcessJob(
                processService,
                commandFacade);

        int reconciled = job.runOnce("worker-a", 10);

        assertThat(reconciled).isEqualTo(1);
        InOrder order = inOrder(processService, commandFacade);
        order.verify(processService).claimDue("worker-a", 10);
        order.verify(commandFacade).reconcileClaimed(first);
        order.verify(commandFacade).reconcileClaimed(second);
    }

    private static void assertSchedulerBean(String beanName) {
        assertThat(ReservationDepositProcessConfig.class.getDeclaredMethods())
                .anySatisfy(method -> {
                    Bean bean = method.getAnnotation(Bean.class);
                    assertThat(bean).isNotNull();
                    assertThat(List.of(bean.name())).contains(beanName);
                });
    }
}
