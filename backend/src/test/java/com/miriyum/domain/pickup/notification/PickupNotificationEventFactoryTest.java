package com.miriyum.domain.pickup.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.pickup.entity.PickupItemSnapshot;
import com.miriyum.domain.pickup.entity.PickupReservation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PickupNotificationEventFactoryTest {

    static final Instant CREATED_AT = Instant.parse("2026-08-01T09:00:00Z");
    static final Instant TERMINAL_AT = Instant.parse("2026-08-01T10:00:00Z");

    private final PickupNotificationEventFactory factory =
            new PickupNotificationEventFactory();

    @Test
    void confirmedEventUsesStableIdentityAndWholeSecondPayload() {
        PickupReservation pickup = confirmedPickup();

        NotificationSourceEventV1 event = factory.confirmed(
                pickup,
                Instant.parse("2026-08-01T09:00:00.987654321Z"),
                "request-create-1"
        );

        assertThat(event.sourceEventId()).isEqualTo("pickup-reservation:77:confirmed");
        assertThat(event.sourceDomain()).isEqualTo(NotificationSourceDomain.PICKUP);
        assertThat(event.purpose())
                .isEqualTo(NotificationPurpose.PICKUP_RESERVATION_CONFIRMED);
        assertThat(event.recipientAccountId()).isEqualTo("11");
        assertThat(event.recipientRelationVersion()).isEqualTo(1L);
        assertThat(event.resourceType()).isEqualTo(NotificationResourceType.PICKUP_RESERVATION);
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
        PickupReservation pickup = confirmedPickup();
        pickup.cancelByConsumer(null, TERMINAL_AT);

        NotificationSourceEventV1 event = factory.cancelled(
                pickup,
                Instant.parse("2026-08-01T10:00:00.123Z"),
                "request-cancel-1"
        );

        assertThat(event.sourceEventId()).isEqualTo("pickup-reservation:77:cancelled");
        assertThat(event.purpose())
                .isEqualTo(NotificationPurpose.PICKUP_RESERVATION_CANCELLED);
        assertThat(event.resourceVersion()).isEqualTo(2L);
        assertThat(event.sourceState()).isEqualTo("CANCELLED");
        assertThat(event.occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-01T10:00:00Z"));
        assertThat(event.correlationId()).isEqualTo("request-cancel-1");
    }

    static PickupReservation confirmedPickup() {
        PickupItemSnapshot item = new PickupItemSnapshot(
                33L,
                44L,
                5L,
                "바질 토스트",
                12_000,
                6L,
                LocalDate.of(2026, 8, 10),
                LocalTime.NOON,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(14, 0),
                2
        );
        PickupReservation pickup = PickupReservation.confirm(
                11L,
                22L,
                "미리윰 식당",
                "Asia/Seoul",
                LocalDate.of(2026, 8, 10),
                LocalTime.NOON,
                Instant.parse("2026-08-10T03:00:00Z"),
                "pickup-acquire-1",
                List.of(item),
                CREATED_AT
        );
        ReflectionTestUtils.setField(pickup, "id", 77L);
        return pickup;
    }
}
