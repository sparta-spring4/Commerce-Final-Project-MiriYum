package com.miriyum.domain.auth.qrepoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class ValkeyConsumerQrEpochStoreNoResponseTest {

    private static final String GENERATION = "restore-generation-v1";
    private static final ParsedToken REFRESH = new ParsedToken(
            TokenNamespace.CONSUMER, 987654321L, "family-secret", "token-secret");

    @Mock
    private StringRedisTemplate redisTemplate;

    private ValkeyConsumerQrEpochStore store;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        ConsumerQrEpochProperties properties = new ConsumerQrEpochProperties();
        properties.setStorageGeneration(GENERATION);
        store = new ValkeyConsumerQrEpochStore(redisTemplate, properties);
        logger = (Logger) LoggerFactory.getLogger(ValkeyConsumerQrEpochStore.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void captureNoResponseFailsClosedWithoutLoggingIdentifiers() {
        given(redisTemplate.<String>execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(null);

        assertUnavailable(() -> store.captureCurrent(REFRESH.accountId()));

        assertSafeSingleLog("operation=capture");
    }

    @Test
    void logoutUnexpectedResultFailsClosedWithoutLoggingIdentifiers() {
        given(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(99L);

        assertUnavailable(() -> store.advanceForLogout(
                TokenNamespace.CONSUMER, REFRESH, "raw-refresh-secret", Instant.parse("2026-08-14T00:00:00Z")));

        assertSafeSingleLog("operation=advanceForLogout", "namespace=consumer", "result=99");
    }

    @Test
    void logoutLargeUnexpectedResultCannotWrapToApplied() {
        given(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(4_294_967_297L);

        assertUnavailable(() -> store.advanceForLogout(
                TokenNamespace.CONSUMER, REFRESH, "raw-refresh-secret", Instant.parse("2026-08-14T00:00:00Z")));

        assertSafeSingleLog(
                "operation=advanceForLogout", "namespace=consumer", "result=4294967297");
    }

    @Test
    void logoutMissingActiveIndexLogsDedicatedNonSensitiveSignal() {
        given(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(-2L);

        assertUnavailable(() -> store.advanceForLogout(
                TokenNamespace.CONSUMER, REFRESH, "raw-refresh-secret", Instant.parse("2026-08-14T00:00:00Z")));

        assertSafeSingleLog(
                "event=qr_epoch_refresh_index_mismatch",
                "operation=advanceForLogout",
                "namespace=consumer",
                "expected=present");
    }

    @Test
    void logoutCompletedMarkerWithStaleIndexLogsDedicatedNonSensitiveSignal() {
        given(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(-3L);

        assertUnavailable(() -> store.advanceForLogout(
                TokenNamespace.CONSUMER, REFRESH, "raw-refresh-secret", Instant.parse("2026-08-14T00:00:00Z")));

        assertSafeSingleLog(
                "event=qr_epoch_refresh_index_mismatch",
                "operation=advanceForLogout",
                "namespace=consumer",
                "expected=absent");
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private void assertSafeSingleLog(String... requiredFragments) {
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message).contains(requiredFragments);
                    assertThat(message).doesNotContain(
                            REFRESH.accountId().toString(),
                            REFRESH.familyId(),
                            REFRESH.tokenId(),
                            "raw-refresh-secret",
                            GENERATION);
                });
    }
}
