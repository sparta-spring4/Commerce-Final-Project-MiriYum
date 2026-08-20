package com.miriyum.domain.reservation.config;

import com.miriyum.domain.reservation.service.ReservationDepositProcessJob;
import com.miriyum.domain.reservation.service.ReservationDepositRefundJob;
import com.miriyum.domain.reservation.service.ReservationDepositDispositionJob;
import com.miriyum.domain.reservation.service.ReservationPaymentRecoveryHandoffJob;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Reservation-owned operational timing for deposit process workers. */
@Configuration
@EnableScheduling
public class ReservationDepositProcessConfig {

    @Bean("reservationDepositDispositionLeaseDuration")
    public Duration reservationDepositDispositionLeaseDuration() {
        return Duration.ofSeconds(30);
    }

    @Bean("reservationDepositRefundLeaseDuration")
    public Duration reservationDepositRefundLeaseDuration() {
        return Duration.ofSeconds(30);
    }

    @Bean("reservationDepositProcessLeaseDuration")
    public Duration reservationDepositProcessLeaseDuration() {
        return Duration.ofSeconds(30);
    }

    @Bean("reservationPaymentRecoveryHandoffLeaseDuration")
    public Duration reservationPaymentRecoveryHandoffLeaseDuration() {
        return Duration.ofSeconds(30);
    }

    @Bean("reservationDepositProcessPollDelay")
    public Duration reservationDepositProcessPollDelay() {
        return Duration.ofSeconds(5);
    }

    @Bean("reservationDepositProcessBatchSize")
    Integer reservationDepositProcessBatchSize(
            @Value("${miriyum.reservation.deposit-worker.process-batch-size:100}") int value
    ) {
        return requirePositive(value, "process-batch-size");
    }

    @Bean("reservationDepositRefundBatchSize")
    Integer reservationDepositRefundBatchSize(
            @Value("${miriyum.reservation.deposit-worker.refund-batch-size:100}") int value
    ) {
        return requirePositive(value, "refund-batch-size");
    }

    @Bean("reservationDepositDispositionBatchSize")
    Integer reservationDepositDispositionBatchSize(
            @Value("${miriyum.reservation.deposit-worker.disposition-batch-size:100}")
            int value
    ) {
        return requirePositive(value, "disposition-batch-size");
    }

    @Bean("reservationPaymentRecoveryHandoffBatchSize")
    Integer reservationPaymentRecoveryHandoffBatchSize(
            @Value("${miriyum.reservation.deposit-worker.recovery-handoff-batch-size:100}")
            int value
    ) {
        return requirePositive(value, "recovery-handoff-batch-size");
    }

    @Bean("reservationDepositProcessPollDelayMs")
    Long reservationDepositProcessPollDelayMs(
            @Value("${miriyum.reservation.deposit-worker.process-poll-delay-ms:5000}") long value
    ) {
        return requirePositive(value, "process-poll-delay-ms");
    }

    @Bean("reservationDepositRefundPollDelayMs")
    Long reservationDepositRefundPollDelayMs(
            @Value("${miriyum.reservation.deposit-worker.refund-poll-delay-ms:5000}") long value
    ) {
        return requirePositive(value, "refund-poll-delay-ms");
    }

    @Bean("reservationDepositDispositionPollDelayMs")
    Long reservationDepositDispositionPollDelayMs(
            @Value("${miriyum.reservation.deposit-worker.disposition-poll-delay-ms:5000}")
            long value
    ) {
        return requirePositive(value, "disposition-poll-delay-ms");
    }

    @Bean("reservationPaymentRecoveryHandoffPollDelayMs")
    Long reservationPaymentRecoveryHandoffPollDelayMs(
            @Value("${miriyum.reservation.deposit-worker.recovery-handoff-poll-delay-ms:5000}")
            long value
    ) {
        return requirePositive(value, "recovery-handoff-poll-delay-ms");
    }

    @Bean
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = false)
    ProcessScheduledWorker reservationDepositProcessScheduledWorker(
            ReservationDepositProcessJob job
    ) {
        return new ProcessScheduledWorker(job);
    }

    @Bean
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = false)
    RefundScheduledWorker reservationDepositRefundScheduledWorker(
            ReservationDepositRefundJob job
    ) {
        return new RefundScheduledWorker(job);
    }

    @Bean
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = false)
    DispositionScheduledWorker reservationDepositDispositionScheduledWorker(
            ReservationDepositDispositionJob job
    ) {
        return new DispositionScheduledWorker(job);
    }

    @Bean
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = false)
    RecoveryHandoffScheduledWorker reservationPaymentRecoveryHandoffScheduledWorker(
            ReservationPaymentRecoveryHandoffJob job
    ) {
        return new RecoveryHandoffScheduledWorker(job);
    }

    @Bean(
            name = "reservationDepositProcessScheduler",
            destroyMethod = "shutdown",
            defaultCandidate = false)
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = false)
    ThreadPoolTaskScheduler reservationDepositProcessScheduler() {
        return scheduler("reservation-deposit-process-");
    }

    @Bean(
            name = "reservationDepositRefundScheduler",
            destroyMethod = "shutdown",
            defaultCandidate = false)
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = false)
    ThreadPoolTaskScheduler reservationDepositRefundScheduler() {
        return scheduler("reservation-deposit-refund-");
    }

    @Bean(
            name = "reservationDepositDispositionScheduler",
            destroyMethod = "shutdown",
            defaultCandidate = false)
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = false)
    ThreadPoolTaskScheduler reservationDepositDispositionScheduler() {
        return scheduler("reservation-deposit-disposition-");
    }

    @Bean(
            name = "reservationPaymentRecoveryHandoffScheduler",
            destroyMethod = "shutdown",
            defaultCandidate = false)
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = false)
    ThreadPoolTaskScheduler reservationPaymentRecoveryHandoffScheduler() {
        return scheduler("reservation-payment-recovery-handoff-");
    }

    private static ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    private static int requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }

    /** Conditional scheduling adapter; the process job remains directly callable. */
    public static final class ProcessScheduledWorker {

        private final ReservationDepositProcessJob job;

        public ProcessScheduledWorker(ReservationDepositProcessJob job) {
            this.job = job;
        }

        @Scheduled(
                scheduler = "reservationDepositProcessScheduler",
                fixedDelayString = "#{@reservationDepositProcessPollDelayMs}",
                initialDelayString = "#{@reservationDepositProcessPollDelayMs}")
        public void runScheduled() {
            job.runScheduled();
        }
    }

    /** Conditional scheduling adapter; the refund job remains directly callable. */
    public static final class RefundScheduledWorker {

        private final ReservationDepositRefundJob job;

        public RefundScheduledWorker(ReservationDepositRefundJob job) {
            this.job = job;
        }

        @Scheduled(
                scheduler = "reservationDepositRefundScheduler",
                fixedDelayString = "#{@reservationDepositRefundPollDelayMs}",
                initialDelayString = "#{@reservationDepositRefundPollDelayMs}")
        public void runScheduled() {
            job.runScheduled();
        }
    }

    /** Conditional scheduling adapter; the disposition job remains directly callable. */
    public static final class DispositionScheduledWorker {

        private final ReservationDepositDispositionJob job;

        public DispositionScheduledWorker(ReservationDepositDispositionJob job) {
            this.job = job;
        }

        @Scheduled(
                scheduler = "reservationDepositDispositionScheduler",
                fixedDelayString = "#{@reservationDepositDispositionPollDelayMs}",
                initialDelayString = "#{@reservationDepositDispositionPollDelayMs}")
        public void runScheduled() {
            job.runScheduled();
        }
    }

    /** Conditional scheduling adapter; the recovery handoff job remains directly callable. */
    public static final class RecoveryHandoffScheduledWorker {

        private final ReservationPaymentRecoveryHandoffJob job;

        public RecoveryHandoffScheduledWorker(ReservationPaymentRecoveryHandoffJob job) {
            this.job = job;
        }

        @Scheduled(
                scheduler = "reservationPaymentRecoveryHandoffScheduler",
                fixedDelayString = "#{@reservationPaymentRecoveryHandoffPollDelayMs}",
                initialDelayString = "#{@reservationPaymentRecoveryHandoffPollDelayMs}")
        public void runScheduled() {
            job.runScheduled();
        }
    }
}
