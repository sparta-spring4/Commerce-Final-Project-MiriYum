package com.miriyum.domain.auth.qrepoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class ConsumerQrEpochServiceTest {

    private static final String OPAQUE_VERSION = "v1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Mock
    private ConsumerQrEpochStore store;

    private ConsumerQrEpochService service;
    private ConsumerQrLogoutCoordinator logoutCoordinator;

    @BeforeEach
    void setUp() {
        service = new ConsumerQrEpochService(store);
        logoutCoordinator = new ConsumerQrLogoutCoordinator(
                store, Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC));
    }

    @Nested
    class EpochValidation {

        @Test
        void capturesCurrentSnapshotFromAuthStore() {
            ConsumerQrEpochSnapshot stored = new ConsumerQrEpochSnapshot(7L, OPAQUE_VERSION);
            given(store.captureCurrent(7L)).willReturn(stored);

            assertThat(service.captureCurrent(7L)).isEqualTo(stored);
        }

        @Test
        void rejectsSnapshotAccountMismatchBeforeStoreLookup() {
            ConsumerQrEpochSnapshot snapshot = new ConsumerQrEpochSnapshot(8L, OPAQUE_VERSION);

            assertStale(() -> service.requireCurrent(7L, snapshot));

            verifyNoInteractions(store);
        }

        @Test
        void rejectsStaleOpaqueVersionWithoutExposingCurrentValue() {
            ConsumerQrEpochSnapshot snapshot = new ConsumerQrEpochSnapshot(7L, OPAQUE_VERSION);
            given(store.isCurrent(7L, OPAQUE_VERSION)).willReturn(false);

            assertStale(() -> service.requireCurrent(7L, snapshot));
        }

        @Test
        void acceptsCurrentOpaqueVersion() {
            ConsumerQrEpochSnapshot snapshot = new ConsumerQrEpochSnapshot(7L, OPAQUE_VERSION);
            given(store.isCurrent(7L, OPAQUE_VERSION)).willReturn(true);

            assertThatCode(() -> service.requireCurrent(7L, snapshot)).doesNotThrowAnyException();
        }
    }

    @Nested
    class LogoutCoordination {

        @Test
        void logsAppliedOutcomeAfterAdvancingAtInjectedClockInstant() {
            assertLogoutOutcome(
                    ConsumerQrEpochAdvanceResult.Status.APPLIED,
                    "event=qr_epoch_logout_result outcome=applied");
        }

        @Test
        void logsAlreadyAppliedOutcomeForIdempotentRetry() {
            assertLogoutOutcome(
                    ConsumerQrEpochAdvanceResult.Status.ALREADY_APPLIED,
                    "event=qr_epoch_logout_result outcome=already_applied");
        }

        @Test
        void logsNotAuthorizedOutcomeForCleanupOnlyRequest() {
            assertLogoutOutcome(
                    ConsumerQrEpochAdvanceResult.Status.NOT_AUTHORIZED,
                    "event=qr_epoch_logout_result outcome=not_authorized");
        }

        private void assertLogoutOutcome(
                ConsumerQrEpochAdvanceResult.Status status,
                String expectedMessage
        ) {
            ParsedToken refresh = new ParsedToken(TokenNamespace.CONSUMER, 7L, "family-id", "token-id");
            given(store.advanceForLogout(
                    TokenNamespace.CONSUMER,
                    refresh,
                    "raw-refresh-token",
                    Instant.parse("2026-08-14T00:00:00Z")))
                    .willReturn(new ConsumerQrEpochAdvanceResult(status));

            List<ILoggingEvent> logs = captureLogoutCoordinatorLogs(() ->
                    logoutCoordinator.advanceForLogout(
                            TokenNamespace.CONSUMER, refresh, "raw-refresh-token"));

            assertThat(logs).singleElement().satisfies(log -> {
                assertThat(log.getLevel()).isEqualTo(Level.INFO);
                assertThat(log.getFormattedMessage()).isEqualTo(expectedMessage);
            });
            verify(store).advanceForLogout(
                    TokenNamespace.CONSUMER,
                    refresh,
                    "raw-refresh-token",
                    Instant.parse("2026-08-14T00:00:00Z"));
        }

        private List<ILoggingEvent> captureLogoutCoordinatorLogs(Runnable action) {
            Logger logger = (Logger) LoggerFactory.getLogger(ConsumerQrLogoutCoordinator.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                action.run();
                return List.copyOf(appender.list);
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    private void assertStale(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.QR_EPOCH_STALE);
    }
}
