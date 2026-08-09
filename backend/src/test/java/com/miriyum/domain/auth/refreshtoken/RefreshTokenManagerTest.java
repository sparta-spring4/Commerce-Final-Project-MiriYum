package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenManagerTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private RefreshTokenIdentityGenerator identityGenerator;

    private RefreshTokenManager manager;

    @BeforeEach
    void setUp() {
        manager = new RefreshTokenManager(
                jwtTokenProvider,
                refreshTokenStore,
                identityGenerator,
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("로그인 성공 전에 Refresh Token family를 Valkey에 저장한다")
    void createsStateBeforeReturningTokenPair() {
        RefreshTokenIdentity identity = new RefreshTokenIdentity("family-1", "token-1");
        given(identityGenerator.generate()).willReturn(identity);
        given(jwtTokenProvider.generateRefreshToken(TokenNamespace.CONSUMER, 7L, "family-1", "token-1"))
                .willReturn("refresh-token");
        given(jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, 7L)).willReturn("access-token");
        given(jwtTokenProvider.getRefreshTokenValiditySeconds()).willReturn(1_209_600L);

        TokenPair pair = manager.issue(TokenNamespace.CONSUMER, 7L);

        ArgumentCaptor<RefreshTokenState> captor = ArgumentCaptor.forClass(RefreshTokenState.class);
        verify(refreshTokenStore).create(captor.capture());
        assertThat(captor.getValue().familyId()).isEqualTo("family-1");
        assertThat(captor.getValue().currentTokenId()).isEqualTo("token-1");
        assertThat(captor.getValue().currentTokenHash()).isEqualTo(RefreshTokenHash.sha256("refresh-token"));
        assertThat(pair.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    @DisplayName("이미 사용한 Refresh Token은 재발급하지 않는다")
    void rejectsReusedRefreshToken() {
        ParsedToken parsed = new ParsedToken(TokenNamespace.CONSUMER, 7L, "family-1", "token-1");
        RefreshTokenIdentity nextIdentity = new RefreshTokenIdentity("new-family-must-not-be-used", "token-2");
        given(identityGenerator.generate()).willReturn(nextIdentity);
        given(jwtTokenProvider.getRefreshTokenValiditySeconds()).willReturn(1_209_600L);
        given(jwtTokenProvider.generateRefreshToken(any(), eq(7L), eq("family-1"), eq("token-2")))
                .willReturn("next-refresh-token");
        given(refreshTokenStore.rotate(
                eq(TokenNamespace.CONSUMER), eq("family-1"), eq(7L), eq("token-1"), any(),
                eq("token-2"), any(), any(), any()))
                .willReturn(new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.REUSED));

        assertThatThrownBy(() -> manager.rotate(TokenNamespace.CONSUMER, parsed, "old-refresh-token"))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID));
    }

    @Test
    @DisplayName("현재 Refresh Token이 일치하면 새 토큰으로 정상 회전한다")
    void rotatesCurrentRefreshToken() {
        ParsedToken parsed = new ParsedToken(TokenNamespace.CONSUMER, 7L, "family-1", "token-1");
        RefreshTokenIdentity nextIdentity = new RefreshTokenIdentity("ignored-family", "token-2");
        given(identityGenerator.generate()).willReturn(nextIdentity);
        given(jwtTokenProvider.getRefreshTokenValiditySeconds()).willReturn(1_209_600L);
        given(jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, 7L)).willReturn("next-access-token");
        given(jwtTokenProvider.generateRefreshToken(TokenNamespace.CONSUMER, 7L, "family-1", "token-2"))
                .willReturn("next-refresh-token");
        given(refreshTokenStore.rotate(
                eq(TokenNamespace.CONSUMER), eq("family-1"), eq(7L), eq("token-1"), any(),
                eq("token-2"), any(), any(), any()))
                .willReturn(new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.ROTATED));

        TokenPair pair = manager.rotate(TokenNamespace.CONSUMER, parsed, "current-refresh-token");

        assertThat(pair.accessToken()).isEqualTo("next-access-token");
        assertThat(pair.refreshToken()).isEqualTo("next-refresh-token");
    }

    @Test
    @DisplayName("로그아웃은 해당 Refresh Token family를 폐기한다")
    void revokesRefreshTokenFamily() {
        ParsedToken parsed = new ParsedToken(TokenNamespace.CONSUMER, 7L, "family-1", "token-1");

        manager.revoke(TokenNamespace.CONSUMER, parsed);

        verify(refreshTokenStore).revoke(eq(TokenNamespace.CONSUMER), eq("family-1"), eq(7L), any());
    }
}
