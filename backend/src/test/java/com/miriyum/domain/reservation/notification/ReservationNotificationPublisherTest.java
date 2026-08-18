package com.miriyum.domain.reservation.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationTaskReceipt;
import com.miriyum.domain.notification.service.NotificationTaskRecorder;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReservationNotificationPublisherTest {

    @Test
    void recordsTheFactoryEventThroughTheNotificationRecorder() {
        CapturingRecorder recorder = new CapturingRecorder();
        ReservationNotificationPublisher publisher = new ReservationNotificationPublisher(
                new ReservationNotificationEventFactory(), recorder
        );
        Reservation reservation = ReservationNotificationEventFactoryTest.confirmedReservation();

        NotificationTaskReceipt receipt = publisher.recordConfirmed(
                reservation,
                ReservationNotificationEventFactoryTest.CREATED_AT,
                "request-create-1"
        );

        assertThat(receipt).isEqualTo(new NotificationTaskReceipt("501", false));
        assertThat(recorder.events).singleElement().satisfies(event -> {
            assertThat(event.sourceEventId()).isEqualTo("reservation:77:confirmed");
            assertThat(event.resourceVersion()).isEqualTo(1L);
        });
    }

    @Test
    void propagatesRecorderFailuresWithoutRemapping() {
        ServiceException failure = new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        NotificationTaskRecorder recorder = event -> {
            throw failure;
        };
        ReservationNotificationPublisher publisher = new ReservationNotificationPublisher(
                new ReservationNotificationEventFactory(), recorder
        );

        assertThatThrownBy(() -> publisher.recordConfirmed(
                ReservationNotificationEventFactoryTest.confirmedReservation(),
                ReservationNotificationEventFactoryTest.CREATED_AT,
                "request-create-1"
        )).isSameAs(failure);
    }

    @Test
    void recordsVisitCompletedAndNoShowThroughTheSamePublicRecorder() {
        CapturingRecorder recorder = new CapturingRecorder();
        ReservationNotificationPublisher publisher = new ReservationNotificationPublisher(
                new ReservationNotificationEventFactory(), recorder
        );
        Reservation fulfilled = ReservationNotificationEventFactoryTest.confirmedReservation();
        fulfilled.fulfill(ReservationNotificationEventFactoryTest.TERMINAL_AT);
        Reservation noShow = ReservationNotificationEventFactoryTest.confirmedReservation();
        noShow.markNoShow(ReservationNotificationEventFactoryTest.TERMINAL_AT);

        publisher.recordVisitCompleted(
                fulfilled, ReservationNotificationEventFactoryTest.TERMINAL_AT,
                "request-fulfill-1");
        publisher.recordNoShow(
                noShow, ReservationNotificationEventFactoryTest.TERMINAL_AT,
                "request-no-show-1");

        assertThat(recorder.events).extracting(NotificationSourceEventV1::purpose)
                .containsExactly(
                        NotificationPurpose.RESERVATION_VISIT_COMPLETED,
                        NotificationPurpose.RESERVATION_NO_SHOW);
    }

    private static final class CapturingRecorder implements NotificationTaskRecorder {

        private final List<NotificationSourceEventV1> events = new ArrayList<>();

        @Override
        public NotificationTaskReceipt record(NotificationSourceEventV1 event) {
            events.add(event);
            return new NotificationTaskReceipt("501", false);
        }
    }
}
