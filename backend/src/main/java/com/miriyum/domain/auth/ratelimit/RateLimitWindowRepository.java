package com.miriyum.domain.auth.ratelimit;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요청 제한 윈도우를 MySQL에서 원자적으로 갱신한다. 여러 인스턴스가 같은 키를 동시에
 * 갱신해도 {@code ON DUPLICATE KEY UPDATE}의 행 잠금으로 카운트가 유실되지 않는다.
 */
@Repository
public interface RateLimitWindowRepository extends JpaRepository<RateLimitWindow, String> {

    @Modifying
    @Transactional
    @Query(
            value = """
                    INSERT INTO rate_limit_windows (rate_limit_key, window_expires_at, request_count)
                    VALUES (:key, :newExpiresAt, 1)
                    ON DUPLICATE KEY UPDATE
                        request_count = IF(window_expires_at <= :now, 1, request_count + 1),
                        window_expires_at = IF(window_expires_at <= :now, :newExpiresAt, window_expires_at)
                    """,
            nativeQuery = true)
    void upsertWindow(
            @Param("key") String key,
            @Param("now") LocalDateTime now,
            @Param("newExpiresAt") LocalDateTime newExpiresAt);
}
