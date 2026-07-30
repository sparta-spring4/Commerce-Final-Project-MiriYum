package com.miriyum.domain.store.menu.service;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MenuDatabaseClock {

    private final JdbcTemplate jdbcTemplate;

    public Instant now() {
        Timestamp value = jdbcTemplate.queryForObject(
                "SELECT UTC_TIMESTAMP(6)", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("database clock returned null");
        }
        return value.toInstant();
    }
}
