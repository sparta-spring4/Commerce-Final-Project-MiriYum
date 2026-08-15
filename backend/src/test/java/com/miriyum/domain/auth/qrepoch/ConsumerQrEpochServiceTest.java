package com.miriyum.domain.auth.qrepoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        void advancesForLogoutAtInjectedClockInstant() {
            ParsedToken refresh = new ParsedToken(TokenNamespace.CONSUMER, 7L, "family-id", "token-id");
            ConsumerQrEpochAdvanceResult applied = new ConsumerQrEpochAdvanceResult(
                    ConsumerQrEpochAdvanceResult.Status.APPLIED);
            given(store.advanceForLogout(
                    TokenNamespace.CONSUMER,
                    refresh,
                    "raw-refresh-token",
                    Instant.parse("2026-08-14T00:00:00Z")))
                    .willReturn(applied);

            assertThatCode(() -> logoutCoordinator.advanceForLogout(
                    TokenNamespace.CONSUMER, refresh, "raw-refresh-token"))
                    .doesNotThrowAnyException();
        }
    }

    private void assertStale(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.QR_EPOCH_STALE);
    }
}
