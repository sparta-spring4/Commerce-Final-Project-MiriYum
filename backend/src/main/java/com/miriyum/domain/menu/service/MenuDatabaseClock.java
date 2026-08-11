package com.miriyum.domain.menu.service;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MenuDatabaseClock {

    private final JdbcTemplate jdbcTemplate;

    public Instant now() {
        BigDecimal value = jdbcTemplate.queryForObject(
                "SELECT UNIX_TIMESTAMP(NOW(6))", BigDecimal.class);
        if (value == null) {
            throw new IllegalStateException("database clock returned null");
        }
        long seconds = value.longValue();
        int nanos = value.subtract(BigDecimal.valueOf(seconds))
                .movePointRight(9)
                .intValue();
        return Instant.ofEpochSecond(seconds, nanos);
    }
}
