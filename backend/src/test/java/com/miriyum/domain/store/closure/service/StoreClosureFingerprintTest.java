package com.miriyum.domain.store.closure.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.closure.dto.TemporaryClosureEndAtRequest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class StoreClosureFingerprintTest {

    @Test
    void temporaryEndChangeReasonParticipatesInIdempotencyFingerprint() {
        OffsetDateTime endAt = OffsetDateTime.parse("2026-08-03T20:00:00+09:00");

        String first = StoreClosureFingerprint.temporaryEnd(
                7L, 3L, new TemporaryClosureEndAtRequest(endAt, "정비 연장"));
        String second = StoreClosureFingerprint.temporaryEnd(
                7L, 3L, new TemporaryClosureEndAtRequest(endAt, "행사 연장"));

        assertThat(first).isNotEqualTo(second);
    }
}
