package com.miriyum.domain.notification.service;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 작업 임대와 재시도 경계를 모든 인스턴스가 같은 MySQL 시각으로 판단하게 한다.
 */
@Component
public class NotificationDatabaseClock {

    private final JdbcTemplate jdbcTemplate;

    public NotificationDatabaseClock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Instant now() {
        BigDecimal value = jdbcTemplate.queryForObject(
                "SELECT UNIX_TIMESTAMP(NOW(6))", BigDecimal.class);
        if (value == null) {
            throw new IllegalStateException("notification database clock returned null");
        }
        long seconds = value.longValue();
        int nanos = value.subtract(BigDecimal.valueOf(seconds))
                .movePointRight(9)
                .intValue();
        return Instant.ofEpochSecond(seconds, nanos);
    }
}
