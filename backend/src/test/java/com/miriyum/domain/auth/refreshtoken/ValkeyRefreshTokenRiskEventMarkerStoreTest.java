package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import io.lettuce.core.ValueScanCursor;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class ValkeyRefreshTokenRiskEventMarkerStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisConnection redisConnection;

    @Test
    @DisplayName("native SSCAN 실패를 서비스 이용 불가 오류로 변환한다")
    void convertsNativeSscanFailureToServiceUnavailable() {
        given(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisCallback<ValueScanCursor<byte[]>>>any()))
                .willThrow(new CompletionException(new IllegalStateException("valkey unavailable")));
        ValkeyRefreshTokenRiskEventMarkerStore markerStore =
                new ValkeyRefreshTokenRiskEventMarkerStore(redisTemplate);

        assertThatThrownBy(markerStore::findPendingEvents)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("cluster 명령 객체가 아니면 서비스 이용 불가 오류로 변환한다")
    void convertsUnexpectedNativeConnectionToServiceUnavailable() {
        given(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisCallback<ValueScanCursor<byte[]>>>any()))
                .willAnswer(invocation -> invocation
                        .<RedisCallback<ValueScanCursor<byte[]>>>getArgument(0)
                        .doInRedis(redisConnection));
        given(redisConnection.getNativeConnection()).willReturn(new Object());
        ValkeyRefreshTokenRiskEventMarkerStore markerStore =
                new ValkeyRefreshTokenRiskEventMarkerStore(redisTemplate);

        assertThatThrownBy(markerStore::findPendingEvents)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }
}
