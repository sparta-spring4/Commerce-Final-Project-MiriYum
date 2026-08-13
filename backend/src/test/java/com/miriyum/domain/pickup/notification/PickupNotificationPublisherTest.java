package com.miriyum.domain.pickup.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationTaskReceipt;
import com.miriyum.domain.notification.service.NotificationTaskRecorder;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PickupNotificationPublisherTest {

    @Test
    void recordsTheFactoryEventThroughTheNotificationRecorder() {
        CapturingRecorder recorder = new CapturingRecorder();
        PickupNotificationPublisher publisher = new PickupNotificationPublisher(
                new PickupNotificationEventFactory(), recorder
        );

        NotificationTaskReceipt receipt = publisher.recordConfirmed(
                PickupNotificationEventFactoryTest.confirmedPickup(),
                PickupNotificationEventFactoryTest.CREATED_AT,
                "request-create-1"
        );

        assertThat(receipt).isEqualTo(new NotificationTaskReceipt("601", false));
        assertThat(recorder.events).singleElement().satisfies(event -> {
            assertThat(event.sourceEventId()).isEqualTo("pickup-reservation:77:confirmed");
            assertThat(event.resourceVersion()).isEqualTo(1L);
        });
    }

    @Test
    void propagatesRecorderFailuresWithoutRemapping() {
        ServiceException failure = new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        NotificationTaskRecorder recorder = event -> {
            throw failure;
        };
        PickupNotificationPublisher publisher = new PickupNotificationPublisher(
                new PickupNotificationEventFactory(), recorder
        );

        assertThatThrownBy(() -> publisher.recordConfirmed(
                PickupNotificationEventFactoryTest.confirmedPickup(),
                PickupNotificationEventFactoryTest.CREATED_AT,
                "request-create-1"
        )).isSameAs(failure);
    }

    private static final class CapturingRecorder implements NotificationTaskRecorder {

        private final List<NotificationSourceEventV1> events = new ArrayList<>();

        @Override
        public NotificationTaskReceipt record(NotificationSourceEventV1 event) {
            events.add(event);
            return new NotificationTaskReceipt("601", false);
        }
    }
}
