package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.notification.config.NotificationSettings;
import com.miriyum.domain.notification.config.NotificationSettings.RuntimePolicy;
import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.port.MenuHoldNotificationSource;
import com.miriyum.domain.notification.port.PickupNotificationSource;
import com.miriyum.domain.notification.port.ReservationNotificationSource;
import com.miriyum.domain.notification.port.WaitingNotificationSource;
import com.miriyum.domain.notification.entity.NotificationTaskStatus;
import com.miriyum.domain.notification.repository.NotificationChannelAttemptRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.DeliveryCompletion;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.DueTask;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.LeasedTask;
import com.miriyum.domain.notification.repository.NotificationTaskTransitionAuditRepository;
import com.miriyum.global.sse.SseWakeUpRequester;
import com.miriyum.global.sse.SseWakeUpTarget;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationDeliveryServiceTest {

    @Test
    void deliveredInAppTaskRequestsAccountWakeUpAfterAllDatabaseWritesSucceed() {
        NotificationTaskRepository tasks = mock(NotificationTaskRepository.class);
        NotificationChannelAttemptRepository attempts = mock(NotificationChannelAttemptRepository.class);
        NotificationTaskTransitionAuditRepository audits =
                mock(NotificationTaskTransitionAuditRepository.class);
        NotificationDatabaseClock databaseClock = mock(NotificationDatabaseClock.class);
        NotificationSourceRegistry sources = mock(NotificationSourceRegistry.class);
        NotificationTitleRenderer titles = mock(NotificationTitleRenderer.class);
        TransactionTemplate transactions = immediateTransactions();
        RecordingWakeUps wakeUps = new RecordingWakeUps();
        Instant now = Instant.parse("2026-08-19T01:00:00Z");
        DueTask due = new DueTask(
                109L, NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED, 41L, 1L,
                NotificationResourceType.PICKUP_RESERVATION, 71L, 1L,
                "CONFIRMED", null, "correlation", 0, 3L, false);
        LeasedTask leased = new LeasedTask(
                109L, due.sourceDomain(), due.purpose(), 41L, 1L,
                due.resourceType(), 71L, 1L, "CONFIRMED", null,
                "correlation", 1, "lease", 4L);
        NotificationSourceContextV1 context = new NotificationSourceContextV1(
                NotificationSourceReadResult.FOUND,
                1L, 1L, "CONFIRMED", "store", null, null,
                null, null, null, null, null);
        given(databaseClock.now()).willReturn(now);
        given(tasks.findNextDueForUpdate(now)).willReturn(Optional.of(due), Optional.empty());
        given(tasks.claim(any(), any(), any(), any(Long.class))).willReturn(leased);
        given(sources.readContext(
                due.sourceDomain(), due.purpose(), due.resourceType(), 71L, 1L, 41L))
                .willReturn(context);
        given(titles.render(due.purpose(), context)).willReturn("픽업 예약이 확정되었습니다.");
        given(tasks.completeDelivery(leased, "픽업 예약이 확정되었습니다.", null))
                .willReturn(Optional.of(new DeliveryCompletion(NotificationTaskStatus.DELIVERED, null)));
        NotificationDeliveryService service = new NotificationDeliveryService(
                tasks, attempts, audits, databaseClock, sources, titles, transactions, wakeUps);

        int delivered = service.deliverDueBatch(new RuntimePolicy(
                "v1", "worker", 1, Duration.ofSeconds(30), 3,
                Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(1)));

        assertThat(delivered).isOne();
        assertThat(wakeUps.targets).containsExactly(
                List.of(SseWakeUpTarget.notificationAccount(41L)));
    }

    @Test
    void workerDoesNotClaimTasksWhenRuntimePolicyIsMissing() {
        NotificationDeliveryService deliveryService = mock(NotificationDeliveryService.class);
        NotificationTaskWorker worker = new NotificationTaskWorker(
                new NotificationSettings(
                        false, null, null, null, null, null, null, null, null, null),
                deliveryService
        );

        assertThat(worker.deliverDueBatch()).isZero();
        verifyNoInteractions(deliveryService);
    }

    @Test
    void workerUsesOneValidatedPolicySnapshotForTheBatch() {
        NotificationDeliveryService deliveryService = mock(NotificationDeliveryService.class);
        given(deliveryService.deliverDueBatch(any())).willReturn(2);
        NotificationTaskWorker worker = new NotificationTaskWorker(
                new NotificationSettings(
                        true,
                        "notification-worker-v1",
                        "worker-a",
                        10,
                        30_000L,
                        3,
                        5_000L,
                        60_000L,
                        1_000L,
                        1_000L
                ),
                deliveryService
        );

        assertThat(worker.deliverDueBatch()).isEqualTo(2);
    }

    @Test
    void pickupResourceNeverFallsBackToMenuHoldSource() {
        ReservationNotificationSource reservation =
                mock(ReservationNotificationSource.class);
        MenuHoldNotificationSource menuHold = mock(MenuHoldNotificationSource.class);
        PickupNotificationSource pickup = mock(PickupNotificationSource.class);
        WaitingNotificationSource waiting = mock(WaitingNotificationSource.class);
        NotificationSourceContextV1 context = mock(NotificationSourceContextV1.class);
        given(pickup.readContext("21", 3L, "11")).willReturn(context);
        NotificationSourceRegistry registry = new NotificationSourceRegistry(
                Optional.of(reservation), Optional.of(menuHold), Optional.of(pickup),
                Optional.of(waiting));

        assertThat(registry.readContext(
                NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                NotificationResourceType.PICKUP_RESERVATION,
                21L,
                3L,
                11L
        )).isSameAs(context);
        verifyNoInteractions(menuHold);
        assertThatThrownBy(() -> registry.readContext(
                NotificationSourceDomain.MENU_HOLD,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                NotificationResourceType.PICKUP_RESERVATION,
                21L,
                3L,
                11L
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void waitingTeamUsesOnlyTheWaitingSource() {
        ReservationNotificationSource reservation = mock(ReservationNotificationSource.class);
        MenuHoldNotificationSource menuHold = mock(MenuHoldNotificationSource.class);
        PickupNotificationSource pickup = mock(PickupNotificationSource.class);
        WaitingNotificationSource waiting = mock(WaitingNotificationSource.class);
        NotificationSourceContextV1 context = mock(NotificationSourceContextV1.class);
        NotificationSourceContextV1 deliveryContext = mock(NotificationSourceContextV1.class);
        given(waiting.readContext(
                NotificationPurpose.WAITING_CALLED, "31", 4L, "11"))
                .willReturn(context);
        given(waiting.readContextForDelivery(
                NotificationPurpose.WAITING_CALLED, "31", 4L, "11"))
                .willReturn(deliveryContext);
        NotificationSourceRegistry registry = new NotificationSourceRegistry(
                Optional.of(reservation), Optional.of(menuHold), Optional.of(pickup),
                Optional.of(waiting));

        assertThat(registry.readContext(
                NotificationSourceDomain.WAITING,
                NotificationPurpose.WAITING_CALLED,
                NotificationResourceType.WAITING_TEAM,
                31L,
                4L,
                11L
        )).isSameAs(context);
        assertThat(registry.readContextForDelivery(
                NotificationSourceDomain.WAITING,
                NotificationPurpose.WAITING_CALLED,
                NotificationResourceType.WAITING_TEAM,
                31L,
                4L,
                11L
        )).isSameAs(deliveryContext);
        verifyNoInteractions(reservation, menuHold, pickup);
    }

    @Test
    void reservationSourceReceivesTheNotificationPurpose() {
        ReservationNotificationSource reservation = mock(ReservationNotificationSource.class);
        NotificationSourceContextV1 context = mock(NotificationSourceContextV1.class);
        NotificationSourceContextV1 deliveryContext = mock(NotificationSourceContextV1.class);
        given(reservation.readContext(
                NotificationPurpose.RESERVATION_VISIT_COMPLETED, "77", 2L, "11"))
                .willReturn(context);
        given(reservation.readContextForDelivery(
                NotificationPurpose.RESERVATION_VISIT_COMPLETED, "77", 2L, "11"))
                .willReturn(deliveryContext);
        NotificationSourceRegistry registry = new NotificationSourceRegistry(
                Optional.of(reservation), Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(registry.readContext(
                NotificationSourceDomain.RESERVATION,
                NotificationPurpose.RESERVATION_VISIT_COMPLETED,
                NotificationResourceType.RESERVATION,
                77L,
                2L,
                11L
        )).isSameAs(context);
        assertThat(registry.readContextForDelivery(
                NotificationSourceDomain.RESERVATION,
                NotificationPurpose.RESERVATION_VISIT_COMPLETED,
                NotificationResourceType.RESERVATION,
                77L,
                2L,
                11L
        )).isSameAs(deliveryContext);
        verify(reservation).readContext(
                NotificationPurpose.RESERVATION_VISIT_COMPLETED, "77", 2L, "11");
        verify(reservation).readContextForDelivery(
                NotificationPurpose.RESERVATION_VISIT_COMPLETED, "77", 2L, "11");
    }

    @SuppressWarnings("unchecked")
    private static TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        given(transactions.execute(any())).willAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return transactions;
    }

    private static final class RecordingWakeUps implements SseWakeUpRequester {
        private final List<List<SseWakeUpTarget>> targets = new ArrayList<>();

        @Override
        public void afterCommit(Collection<SseWakeUpTarget> targets) {
            this.targets.add(List.copyOf(targets));
        }
    }
}
