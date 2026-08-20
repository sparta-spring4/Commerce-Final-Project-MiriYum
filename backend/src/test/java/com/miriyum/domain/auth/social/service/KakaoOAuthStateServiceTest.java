package com.miriyum.domain.auth.social.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.dto.KakaoOAuthState;
import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoOAuthStateServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
    private final KakaoOAuthStateService stateService = new KakaoOAuthStateService(
            "kakao-oauth-state-test-secret-must-be-long-enough", "miriyum", clock);

    @Test
    @DisplayName("일반 사용자 카카오 로그인 state는 계정 유형과 LOGIN 목적을 보존한다")
    void createsConsumerLoginState() {
        String state = stateService.createLoginState(TokenNamespace.CONSUMER);

        KakaoOAuthState parsed = stateService.parse(state);

        assertThat(parsed.namespace()).isEqualTo(TokenNamespace.CONSUMER);
        assertThat(parsed.purpose()).isEqualTo(KakaoOAuthPurpose.LOGIN);
        assertThat(parsed.accountId()).isNull();
    }

    @Test
    @DisplayName("매장 운영자 카카오 연결 state는 연결할 계정 ID를 보존한다")
    void createsStoreOperatorLinkState() {
        String state = stateService.createLinkState(TokenNamespace.STORE_OPERATOR, 42L);

        KakaoOAuthState parsed = stateService.parse(state);

        assertThat(parsed.namespace()).isEqualTo(TokenNamespace.STORE_OPERATOR);
        assertThat(parsed.purpose()).isEqualTo(KakaoOAuthPurpose.LINK);
        assertThat(parsed.accountId()).isEqualTo(42L);
    }
}
