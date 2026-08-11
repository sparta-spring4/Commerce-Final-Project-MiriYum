package com.miriyum.domain.payment.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 내부 aggregate PK와 분리된 공개 숫자 참조를 DB에서 원자적으로 채번한다. */
@Repository
public class PaymentReferenceAllocator {

    private final JdbcTemplate jdbcTemplate;

    public PaymentReferenceAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String nextPaymentId() {
        return nextId("payment_public_ids");
    }

    public String nextRefundId() {
        return nextId("refund_public_ids");
    }

    private String nextId(String table) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + table + " () VALUES ()",
                    Statement.RETURN_GENERATED_KEYS
            );
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("public payment reference was not allocated");
        }
        return key.toString();
    }
}
