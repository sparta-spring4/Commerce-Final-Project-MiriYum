package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.entity.NotificationPurpose;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.entity.NotificationSourceDomain;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class NotificationPayloadFingerprintTest {

    @Test
    void hashesTheVersionedJcsPayloadWithoutLosingLongPrecision() {
        NotificationSourceEventV1 event = event(
                "correlation-first",
                9_007_199_254_740_993L,
                "확정/\"READY\"",
                null,
                null
        );

        assertThat(NotificationPayloadFingerprint.of(event))
                .isEqualTo("5203d1a02341bb69935dbe543df3d01c1c693efba94d78ae4127d5012312f101");
    }

    @Test
    void excludesCorrelationIdFromTheFingerprint() {
        NotificationSourceEventV1 first = event("correlation-first", 7L, "CONFIRMED", null, null);
        NotificationSourceEventV1 replay = event("correlation-replay", 7L, "CONFIRMED", null, null);

        assertThat(NotificationPayloadFingerprint.of(replay))
                .isEqualTo(NotificationPayloadFingerprint.of(first));
    }

    @Test
    void rejectsNonZeroFractionalSecondsInsteadOfRoundingThem() {
        OffsetDateTime fractional = OffsetDateTime.parse("2026-08-12T10:02:03.001+09:00");

        assertThatThrownBy(() -> new NotificationSourceEventV1(
                "pickup-confirmed-1",
                NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                "11",
                7L,
                NotificationResourceType.PICKUP_RESERVATION,
                "21",
                3L,
                "CONFIRMED",
                fractional,
                fractional,
                null,
                null,
                "correlation-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    void rejectsPurposeOwnedByAnotherSourceDomain() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-12T10:02:03+09:00");

        assertThatThrownBy(() -> new NotificationSourceEventV1(
                "pickup-confirmed-1",
                NotificationSourceDomain.RESERVATION,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                "11",
                7L,
                NotificationResourceType.PICKUP_RESERVATION,
                "21",
                3L,
                "CONFIRMED",
                occurredAt,
                occurredAt,
                null,
                null,
                "correlation-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purpose");
    }

    @Test
    void rejectsFutureScheduledAtForImmediatePurpose() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-12T10:02:03+09:00");

        assertThatThrownBy(() -> new NotificationSourceEventV1(
                "pickup-confirmed-1",
                NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                "11",
                7L,
                NotificationResourceType.PICKUP_RESERVATION,
                "21",
                3L,
                "CONFIRMED",
                occurredAt,
                occurredAt.plusMinutes(5),
                null,
                null,
                "correlation-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immediate notification");
    }

    @Test
    void rejectsTimingPolicyVersionForImmediatePurpose() {
        assertThatThrownBy(() -> event(
                "correlation-first",
                7L,
                "CONFIRMED",
                null,
                1L
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immediate notification");
    }

    @Test
    void rejectsUnpairedSurrogatesThatCannotBeCanonicalJcsStrings() {
        NotificationSourceEventV1 event = event(
                "correlation-first",
                7L,
                "CONFIRMED\uD800",
                null,
                null
        );

        assertThatThrownBy(() -> NotificationPayloadFingerprint.of(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceState");
    }

    private static NotificationSourceEventV1 event(
            String correlationId,
            long recipientRelationVersion,
            String sourceState,
            OffsetDateTime expiresAt,
            Long timingPolicyVersion
    ) {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-12T10:02:03+09:00");
        return new NotificationSourceEventV1(
                "pickup-confirmed-1",
                NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                "11",
                recipientRelationVersion,
                NotificationResourceType.PICKUP_RESERVATION,
                "21",
                3L,
                sourceState,
                occurredAt,
                occurredAt,
                expiresAt,
                timingPolicyVersion,
                correlationId
        );
    }
}
