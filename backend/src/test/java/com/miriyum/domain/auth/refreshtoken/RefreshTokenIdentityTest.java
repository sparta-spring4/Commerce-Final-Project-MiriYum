package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenIdentityTest {

    private final RefreshTokenIdentityGenerator generator = new RefreshTokenIdentityGenerator();

    @Test
    @DisplayName("Refresh Token family와 token 식별자를 무작위로 생성한다")
    void generatesOpaqueIdentity() {
        // when
        RefreshTokenIdentity first = generator.generate();
        RefreshTokenIdentity second = generator.generate();

        // then
        assertThat(first.familyId()).isNotBlank();
        assertThat(first.tokenId()).isNotBlank();
        assertThat(first).isNotEqualTo(second);
        assertThat(first.familyId()).doesNotContain("@").doesNotContain("010");
        assertThat(RefreshTokenKey.forFamily(TokenNamespace.CONSUMER, first.familyId()))
                .isEqualTo("auth:refresh:consumer:" + first.familyId());
    }

    @Test
    @DisplayName("Refresh Token 원문은 복원할 수 없는 SHA-256 해시로 변환한다")
    void hashesRefreshTokenWithoutStoringRawValue() {
        // given
        String token = "refresh-token-value";

        // when
        String hash = RefreshTokenHash.sha256(token);

        // then
        assertThat(hash).hasSize(64).containsPattern("^[0-9a-f]+$");
        assertThat(hash).isEqualTo(RefreshTokenHash.sha256(token));
        assertThat(hash).doesNotContain(token);
    }
}
