package com.miriyum.domain.reservation.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.entity.NotificationPurpose;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.entity.NotificationSourceDomain;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationNotificationEventFactoryTest {

    static final Instant CREATED_AT = Instant.parse("2026-08-01T09:00:00Z");
    static final Instant TERMINAL_AT = Instant.parse("2026-08-01T10:00:00Z");

    private final ReservationNotificationEventFactory factory =
            new ReservationNotificationEventFactory();

    @Test
    void confirmedEventUsesStableIdentityAndWholeSecondPayload() {
        Reservation reservation = confirmedReservation();

        NotificationSourceEventV1 event = factory.confirmed(
                reservation,
                Instant.parse("2026-08-01T09:00:00.987654321Z"),
                "request-create-1"
        );

        assertThat(event.sourceEventId()).isEqualTo("reservation:77:confirmed");
        assertThat(event.sourceDomain()).isEqualTo(NotificationSourceDomain.RESERVATION);
        assertThat(event.purpose()).isEqualTo(NotificationPurpose.RESERVATION_CONFIRMED);
        assertThat(event.recipientAccountId()).isEqualTo("11");
        assertThat(event.recipientRelationVersion()).isEqualTo(1L);
        assertThat(event.resourceType()).isEqualTo(NotificationResourceType.RESERVATION);
        assertThat(event.resourceId()).isEqualTo("77");
        assertThat(event.resourceVersion()).isEqualTo(1L);
        assertThat(event.sourceState()).isEqualTo("CONFIRMED");
        assertThat(event.occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-01T09:00:00Z"));
        assertThat(event.scheduledAt()).isEqualTo(event.occurredAt());
        assertThat(event.expiresAt()).isNull();
        assertThat(event.timingPolicyVersion()).isNull();
        assertThat(event.correlationId()).isEqualTo("request-create-1");
    }

    @Test
    void cancelledEventUsesTerminalVersionAndState() {
        Reservation reservation = confirmedReservation();
        reservation.cancel(TERMINAL_AT);

        NotificationSourceEventV1 event = factory.cancelled(
                reservation,
                Instant.parse("2026-08-01T10:00:00.123Z"),
                "request-cancel-1"
        );

        assertThat(event.sourceEventId()).isEqualTo("reservation:77:cancelled");
        assertThat(event.purpose()).isEqualTo(NotificationPurpose.RESERVATION_CANCELLED);
        assertThat(event.resourceVersion()).isEqualTo(2L);
        assertThat(event.sourceState()).isEqualTo("CANCELLED");
        assertThat(event.occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-01T10:00:00Z"));
        assertThat(event.scheduledAt()).isEqualTo(event.occurredAt());
        assertThat(event.correlationId()).isEqualTo("request-cancel-1");
    }

    static Reservation confirmedReservation() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                22L, 4L, 30, 60, 15
        );
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "notification fixture");
        ReservationTimeSnapshot snapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 3, 18, 0),
                ZoneId.of("Asia/Seoul"),
                null
        );
        Reservation reservation = Reservation.confirm(
                11L,
                22L,
                "미리윰 식당",
                snapshot,
                PartyComposition.of(2, 0, 0),
                ReservationContactSnapshot.contactable("consumer:11:channel:primary"),
                3L,
                new ReservationCancellationPolicyVersion(1L),
                CREATED_AT
        );
        ReflectionTestUtils.setField(reservation, "id", 77L);
        return reservation;
    }
}
