package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenStoreContractTest {

    @Test
    @DisplayName("Valkey 저장 상태는 필수 식별자와 만료 시각을 요구한다")
    void refreshTokenStateRequiresStorageFields() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");

        RefreshTokenState state = new RefreshTokenState(
                TokenNamespace.CONSUMER,
                7L,
                "family-1",
                "token-1",
                "hash-1",
                now.plusSeconds(1_209_600),
                now,
                RefreshTokenState.Status.ACTIVE);

        assertThat(state.namespace()).isEqualTo(TokenNamespace.CONSUMER);
        assertThat(state.accountId()).isEqualTo(7L);
        assertThat(state.status()).isEqualTo(RefreshTokenState.Status.ACTIVE);
    }

    @Test
    @DisplayName("계정 ID가 없거나 식별자가 비어 있으면 저장 상태를 만들 수 없다")
    void rejectsInvalidRefreshTokenState() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> new RefreshTokenState(
                TokenNamespace.CONSUMER,
                0L,
                "family-1",
                "token-1",
                "hash-1",
                now.plusSeconds(60),
                now,
                RefreshTokenState.Status.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RefreshTokenState(
                TokenNamespace.CONSUMER,
                7L,
                " ",
                "token-1",
                "hash-1",
                now.plusSeconds(60),
                now,
                RefreshTokenState.Status.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RefreshTokenState(
                TokenNamespace.CONSUMER,
                7L,
                "family-1",
                "token-1",
                "hash-1",
                now,
                now.plusSeconds(60),
                RefreshTokenState.Status.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Refresh Token 회전 결과는 성공 여부를 명확히 구분한다")
    void distinguishesRotationResult() {
        RefreshTokenRotationResult rotated =
                new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.ROTATED);
        RefreshTokenRotationResult reused =
                new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.REUSED);

        assertThat(rotated.rotated()).isTrue();
        assertThat(reused.rotated()).isFalse();
    }
}
