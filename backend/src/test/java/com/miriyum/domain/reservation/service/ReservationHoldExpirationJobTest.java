package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miriyum.domain.reservation.config.ReservationHoldExpirationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(MockitoExtension.class)
class ReservationHoldExpirationJobTest {

    @Mock
    private ReservationHoldExpirationService expirationService;

    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logger = (Logger) LoggerFactory.getLogger(ReservationHoldExpirationJob.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    @DisplayName("만료 poll은 검증된 batch size로 service 결과를 그대로 반환한다")
    void expirationPollUsesConfiguredBatchSize() {
        ReservationHoldExpirationJob job =
                new ReservationHoldExpirationJob(expirationService, 37);
        given(expirationService.expireDueHolds(37)).willReturn(5);

        int completed = job.expireDueHolds();

        assertThat(completed).isEqualTo(5);
        then(expirationService).should().expireDueHolds(37);
    }

    @Test
    @DisplayName("장기 체류가 있을 때만 식별자 없는 level-triggered 집계 이벤트를 남긴다")
    void reconciliationObservationLogsOnlyPositiveAggregateCount() {
        ReservationHoldExpirationJob job =
                new ReservationHoldExpirationJob(expirationService, 37);
        given(expirationService.countLongStayingReconciliations())
                .willReturn(0L, 3L);

        long empty = job.reportLongStayingReconciliations();
        long stalled = job.reportLongStayingReconciliations();

        assertThat(empty).isZero();
        assertThat(stalled).isEqualTo(3L);
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "event=reservation_hold_reconciliation_stalled long_stay_count=3");
        assertThat(logAppender.list.getFirst().getFormattedMessage())
                .doesNotContain("reservationHoldId", "consumer", "store", "command");
    }

    @Test
    @DisplayName("두 scheduled 작업은 같은 전용 scheduler를 명시한다")
    void scheduledMethodsUseDedicatedScheduler() throws NoSuchMethodException {
        Scheduled expiration = ReservationHoldExpirationJob.class
                .getMethod("expireDueHolds")
                .getAnnotation(Scheduled.class);
        Scheduled reconciliation = ReservationHoldExpirationJob.class
                .getMethod("reportLongStayingReconciliations")
                .getAnnotation(Scheduled.class);

        assertThat(expiration.scheduler())
                .isEqualTo("reservationHoldExpirationScheduler");
        assertThat(reconciliation.scheduler())
                .isEqualTo("reservationHoldExpirationScheduler");
    }

    @Test
    @DisplayName("기본 활성 설정은 다른 scheduled 작업의 기본 후보가 아닌 단일 스레드 scheduler를 만든다")
    void defaultEnabledConfigurationCreatesIsolatedScheduler() {
        new ApplicationContextRunner()
                .withUserConfiguration(ReservationHoldExpirationConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);
                    assertThat(context.getBean("reservationHoldExpirationScheduler"))
                            .isInstanceOf(ThreadPoolTaskScheduler.class);
                    AbstractBeanDefinition schedulerDefinition =
                            (AbstractBeanDefinition) context.getBeanFactory()
                                    .getBeanDefinition("reservationHoldExpirationScheduler");
                    assertThat(schedulerDefinition.isDefaultCandidate()).isFalse();
                    assertThat(context.getBean("reservationHoldExpirationBatchSize"))
                            .isEqualTo(100);
                    assertThat(context.getBean("reservationHoldExpirationPollDelayMs"))
                            .isEqualTo(1_000L);
                    assertThat(context.getBean("reservationHoldReconciliationPollDelayMs"))
                            .isEqualTo(60_000L);
                });
    }

    @Test
    @DisplayName("enabled=false이면 전용 scheduler를 만들지 않는다")
    void disabledConfigurationCreatesNoScheduler() {
        new ApplicationContextRunner()
                .withUserConfiguration(ReservationHoldExpirationConfig.class)
                .withPropertyValues("miriyum.reservation.hold-expiration.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("reservationHoldExpirationScheduler");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "miriyum.reservation.hold-expiration.batch-size=0",
        "miriyum.reservation.hold-expiration.poll-delay-ms=0",
        "miriyum.reservation.hold-expiration.reconciliation-poll-delay-ms=0"
    })
    @DisplayName("batch와 두 poll delay는 각각 양수여야 한다")
    void configurationRejectsEachNonPositiveRuntimeValue(String invalidProperty) {
        new ApplicationContextRunner()
                .withUserConfiguration(ReservationHoldExpirationConfig.class)
                .withPropertyValues(invalidProperty)
                .run(context -> assertThat(context).hasFailed());
    }
}
