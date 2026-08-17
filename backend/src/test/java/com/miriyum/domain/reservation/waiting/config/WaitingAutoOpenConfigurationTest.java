package com.miriyum.domain.reservation.waiting.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenJobRunner;
import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenPlanner;
import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class WaitingAutoOpenConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(WaitingAutoOpenPlanner.class, () -> mock(WaitingAutoOpenPlanner.class))
            .withBean(WaitingAutoOpenService.class, () -> mock(WaitingAutoOpenService.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(WaitingAutoOpenSchedulingConfig.class);

    @Test
    void defaultAndExplicitOffRegisterNoWorker() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(WaitingAutoOpenJobRunner.class)
                .doesNotHaveBean(WaitingAutoOpenProperties.class));
        runner.withPropertyValues("miriyum.waiting.auto-open.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(WaitingAutoOpenJobRunner.class));
    }

    @Test
    void enabledValidConfigurationRegistersWorker() {
        runner.withPropertyValues(validProperties())
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(WaitingAutoOpenJobRunner.class)
                        .hasSingleBean(WaitingAutoOpenProperties.class));
    }

    @Test
    void enabledKeepsTheApplicationSchedulerSeparateFromTheAutoOpenScheduler() {
        runner.withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues(validProperties())
                .run(context -> {
                    assertThat(context).hasBean("taskScheduler");
                    assertThat(context).hasBean("waitingAutoOpenTaskScheduler");
                    assertThat(context.getBean("taskScheduler"))
                            .isNotSameAs(context.getBean("waitingAutoOpenTaskScheduler"));
                });
    }

    @Test
    void shutdownWaitsForAnExecutingAutoOpenTaskToFinish() throws Exception {
        ThreadPoolTaskScheduler scheduler = new WaitingAutoOpenSchedulingConfig()
                .waitingAutoOpenTaskScheduler();
        ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        scheduler.initialize();

        try {
            scheduler.execute(() -> {
                taskStarted.countDown();
                await(releaseTask);
            });
            assertThat(taskStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> shutdown = shutdownExecutor.submit(scheduler::shutdown);

            org.junit.jupiter.api.Assertions.assertThrows(
                    TimeoutException.class,
                    () -> shutdown.get(200, TimeUnit.MILLISECONDS));
            releaseTask.countDown();
            shutdown.get(1, TimeUnit.SECONDS);
        } finally {
            releaseTask.countDown();
            scheduler.shutdown();
            shutdownExecutor.shutdownNow();
        }
    }

    @Test
    void enabledZeroConfigurationFailsStartup() {
        runner.withPropertyValues(
                        "miriyum.waiting.auto-open.enabled=true",
                        "miriyum.waiting.auto-open.worker-id=worker-a",
                        "miriyum.waiting.auto-open.planning-horizon=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] validProperties() {
        return new String[] {
            "miriyum.waiting.auto-open.enabled=true",
            "miriyum.waiting.auto-open.worker-id=worker-a",
            "miriyum.waiting.auto-open.planning-horizon=PT12H",
            "miriyum.waiting.auto-open.planning-batch-size=20",
            "miriyum.waiting.auto-open.claim-batch-size=5",
            "miriyum.waiting.auto-open.lease-duration=PT30S",
            "miriyum.waiting.auto-open.max-attempts=4",
            "miriyum.waiting.auto-open.initial-retry-delay=PT2S",
            "miriyum.waiting.auto-open.maximum-retry-delay=PT1M",
            "miriyum.waiting.auto-open.invalidation-batch-size=10",
            "miriyum.waiting.auto-open.poll-delay=PT5S",
            "miriyum.waiting.auto-open.initial-delay=PT10S"
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
