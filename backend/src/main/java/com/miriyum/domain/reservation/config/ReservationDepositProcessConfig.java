package com.miriyum.domain.reservation.config;

import com.miriyum.domain.reservation.service.ReservationDepositProcessJob;
import com.miriyum.domain.reservation.service.ReservationDepositRefundJob;
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

    @Bean("reservationDepositRefundLeaseDuration")
    public Duration reservationDepositRefundLeaseDuration() {
        return Duration.ofSeconds(30);
    }

    @Bean("reservationDepositProcessLeaseDuration")
    public Duration reservationDepositProcessLeaseDuration() {
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

    @Bean
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = true)
    ProcessScheduledWorker reservationDepositProcessScheduledWorker(
            ReservationDepositProcessJob job
    ) {
        return new ProcessScheduledWorker(job);
    }

    @Bean
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = true)
    RefundScheduledWorker reservationDepositRefundScheduledWorker(
            ReservationDepositRefundJob job
    ) {
        return new RefundScheduledWorker(job);
    }

    @Bean(
            name = "reservationDepositProcessScheduler",
            destroyMethod = "shutdown",
            defaultCandidate = false)
    @ConditionalOnProperty(
            name = "miriyum.reservation.deposit-worker.enabled",
            havingValue = "true",
            matchIfMissing = true)
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
            matchIfMissing = true)
    ThreadPoolTaskScheduler reservationDepositRefundScheduler() {
        return scheduler("reservation-deposit-refund-");
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
}
