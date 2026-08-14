package com.miriyum.domain.platformoperator.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPasswordState;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformOperatorAccountTest {

    @Test
    @DisplayName("임시 비밀번호를 최초 변경하면 정상 상태와 새 세션 버전으로 전환한다")
    void activatesTheFirstPasswordOnce() {
        PlatformOperatorAccount account = temporaryAccount();

        account.changeInitialPassword("{sha256-bcrypt}new-hash");

        assertThat(account.getPasswordHash()).isEqualTo("{sha256-bcrypt}new-hash");
        assertThat(account.getPasswordState()).isEqualTo(PlatformOperatorPasswordState.ACTIVE);
        assertThat(account.getTemporaryPasswordFailureCount()).isZero();
        assertThat(account.getSessionVersion()).isEqualTo(2L);
        assertThatThrownBy(() -> account.changeInitialPassword("again"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    @DisplayName("임시 비밀번호 실패와 만료를 중앙 계정 상태로 판정한다")
    void tracksTemporaryCredentialRisk() {
        PlatformOperatorAccount account = temporaryAccount();

        account.recordTemporaryPasswordFailure();
        account.recordTemporaryPasswordFailure();

        assertThat(account.getTemporaryPasswordFailureCount()).isEqualTo(2);
        assertThat(account.canUseTemporaryPassword(Instant.parse("2026-08-13T00:09:59Z"), 3)).isTrue();
        assertThat(account.canUseTemporaryPassword(Instant.parse("2026-08-13T00:10:00Z"), 3)).isFalse();
        account.recordTemporaryPasswordFailure();
        assertThat(account.canUseTemporaryPassword(Instant.parse("2026-08-13T00:00:00Z"), 3)).isFalse();
    }

    @Test
    @DisplayName("계정 중지와 권한 변경은 기존 세션을 거부하도록 버전을 증가시킨다")
    void advancesVersionsForSecurityChanges() {
        PlatformOperatorAccount account = temporaryAccount();

        account.advanceAuthorityVersion();
        account.suspend();

        assertThat(account.getStatus()).isEqualTo(PlatformOperatorAccountStatus.SUSPENDED);
        assertThat(account.getAuthorityVersion()).isEqualTo(2L);
        assertThat(account.getSessionVersion()).isEqualTo(3L);
    }

    private PlatformOperatorAccount temporaryAccount() {
        return PlatformOperatorAccount.createTemporary(
                "operator@example.com",
                "{sha256-bcrypt}hash",
                "운영자",
                Instant.parse("2026-08-13T00:10:00Z"));
    }
}
