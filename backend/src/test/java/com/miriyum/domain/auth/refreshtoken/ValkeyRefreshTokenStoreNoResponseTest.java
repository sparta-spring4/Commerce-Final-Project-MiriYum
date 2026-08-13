package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Lua 실행이 결과를 돌려주지 않을 때 업무 결과로 접히지 않는지 확인한다.
 *
 * <p>무응답을 {@code NOT_FOUND}로 접으면 Valkey 실행 이상이 "잘못된 Refresh Token"이라는
 * 인증 오류로 숨고, 폐기 경로에서 반환값을 보지 않으면 실제로 폐기되지 않았는데도 성공으로
 * 끝난다. 두 경우 모두 {@code COMMON_012}로 실패해야 한다(PR #208 리뷰).</p>
 */
@ExtendWith(MockitoExtension.class)
class ValkeyRefreshTokenStoreNoResponseTest {

    private static final TokenNamespace NAMESPACE = TokenNamespace.CONSUMER;
    private static final Long ACCOUNT_ID = 7L;
    private static final String FAMILY_ID = "family-1";
    private static final String TOKEN_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Mock
    private StringRedisTemplate redisTemplate;

    private ValkeyRefreshTokenStore store;

    @BeforeEach
    void setUp() {
        store = new ValkeyRefreshTokenStore(redisTemplate);
    }

    @Test
    @DisplayName("create가 무응답이면 COMMON_012로 실패한다")
    void createFailsWhenScriptReturnsNoResult() {
        givenNoScriptResult();

        assertThatThrownBy(() -> store.create(activeState(), 0L))
                .isInstanceOf(ServiceException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("rotate가 무응답이면 NOT_FOUND가 아니라 COMMON_012로 실패한다")
    void rotateFailsWhenScriptReturnsNoResult() {
        givenNoScriptResult();

        assertThatThrownBy(this::rotate)
                .isInstanceOf(ServiceException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("rotate가 0을 반환하면 정상 업무 결과인 NOT_FOUND로 처리한다")
    void rotateReturnsNotFoundWhenFamilyMissing() {
        givenScriptResult(0L);

        assertThat(rotate().status()).isEqualTo(RefreshTokenRotationResult.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("rotate가 계약에 없는 값을 반환하면 COMMON_012로 실패한다")
    void rotateFailsOnUnexpectedScriptResult() {
        givenScriptResult(99L);

        assertThatThrownBy(this::rotate)
                .isInstanceOf(ServiceException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("revoke가 무응답이면 성공으로 끝내지 않고 COMMON_012로 실패한다")
    void revokeFailsWhenScriptReturnsNoResult() {
        givenNoScriptResult();

        assertThatThrownBy(() -> store.revoke(NAMESPACE, FAMILY_ID, ACCOUNT_ID, NOW))
                .isInstanceOf(ServiceException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("revokeAll이 무응답이면 성공으로 끝내지 않고 COMMON_012로 실패한다")
    void revokeAllFailsWhenScriptReturnsNoResult() {
        givenNoScriptResult();

        assertThatThrownBy(() -> store.revokeAll(NAMESPACE, ACCOUNT_ID, NOW, NOW.plusSeconds(3600)))
                .isInstanceOf(ServiceException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("revokeAll이 폐기 0건을 반환해도 정상으로 처리한다")
    void revokeAllAcceptsZeroRevokedFamilies() {
        givenScriptResult(0L);

        store.revokeAll(NAMESPACE, ACCOUNT_ID, NOW, NOW.plusSeconds(3600));
    }

    private void givenNoScriptResult() {
        givenScriptResult(null);
    }

    private void givenScriptResult(Long result) {
        given(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(result);
    }

    private RefreshTokenRotationResult rotate() {
        return store.rotate(
                NAMESPACE, FAMILY_ID, ACCOUNT_ID, "token-1", TOKEN_HASH, "token-2", TOKEN_HASH,
                NOW, NOW.plusSeconds(3600));
    }

    private RefreshTokenState activeState() {
        return new RefreshTokenState(
                NAMESPACE, ACCOUNT_ID, FAMILY_ID, "token-1", TOKEN_HASH,
                NOW.plusSeconds(3600), NOW, RefreshTokenState.Status.ACTIVE);
    }

    private CommonErrorCode errorCodeOf(Throwable throwable) {
        return (CommonErrorCode) ((ServiceException) throwable).getErrorCode();
    }
}
