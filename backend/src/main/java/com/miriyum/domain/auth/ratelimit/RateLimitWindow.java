package com.miriyum.domain.auth.ratelimit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 등급+키별 고정 윈도우 요청 횟수를 MySQL에 저장한다. {@link RateLimitWindowRepository}의
 * 원자적 upsert로만 갱신되며, 애플리케이션 코드에서 직접 필드를 바꾸지 않는다.
 */
@Entity
@Table(name = "rate_limit_windows")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RateLimitWindow {

    @Id
    @Column(name = "rate_limit_key")
    private String key;

    @Column(name = "window_expires_at", nullable = false)
    private LocalDateTime windowExpiresAt;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    /**
     * 테스트에서 값을 채운 인스턴스를 만들기 위한 패키지 전용 생성자다. 운영 코드는 이 값을
     * {@link RateLimitWindowRepository}의 원자적 upsert로만 채우고 직접 구성하지 않는다.
     */
    RateLimitWindow(String key, LocalDateTime windowExpiresAt, int requestCount) {
        this.key = key;
        this.windowExpiresAt = windowExpiresAt;
        this.requestCount = requestCount;
    }
}
