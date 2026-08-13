package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.config.NotificationHistorySettings;
import com.miriyum.domain.notification.entity.NotificationActionAvailability;
import com.miriyum.domain.notification.entity.NotificationActionType;
import com.miriyum.domain.notification.entity.NotificationPurpose;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.entity.NotificationSourceDomain;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.HistoryTask;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationHistoryServiceTest {

    private static final long CONSUMER_ID = 11L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-13T01:02:03Z");

    private final NotificationTaskRepository repository = mock(NotificationTaskRepository.class);
    private final NotificationCursorCodec cursorCodec = mock(NotificationCursorCodec.class);
    private final NotificationSourceRegistry sourceRegistry = mock(NotificationSourceRegistry.class);
    private final NotificationHistoryService service =
            new NotificationHistoryService(repository, cursorCodec, sourceRegistry);

    @Test
    void returnsStablePageAndBuildsNextCursorFromLastVisibleItem() {
        HistoryTask first = historyTask(103L, OCCURRED_AT);
        HistoryTask second = historyTask(102L, OCCURRED_AT.minusSeconds(1));
        HistoryTask lookahead = historyTask(101L, OCCURRED_AT.minusSeconds(2));
        given(repository.findDeliveredInAppHistory(CONSUMER_ID, null, 3))
                .willReturn(List.of(first, second, lookahead));
        given(sourceRegistry.readContext(
                NotificationSourceDomain.PICKUP,
                NotificationResourceType.PICKUP_RESERVATION,
                31L,
                3L,
                CONSUMER_ID
        )).willReturn(foundAction());
        NotificationCursorCodec.Boundary nextBoundary =
                new NotificationCursorCodec.Boundary(second.occurredAt(), second.notificationId());
        given(cursorCodec.encode(CONSUMER_ID, nextBoundary)).willReturn("next_cursor");

        var page = service.getHistory(CONSUMER_ID, null, 2);

        assertThat(page.items()).extracting(item -> item.notificationId())
                .containsExactly("103", "102");
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("next_cursor");
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.action()).isNotNull();
            assertThat(item.action().type())
                    .isEqualTo(NotificationActionType.PICKUP_RESERVATION_DETAIL);
        });
    }

    @Test
    void sourceTemporaryFailureKeepsDeliveredItemAndOmitsAction() {
        HistoryTask task = historyTask(103L, OCCURRED_AT);
        given(repository.findDeliveredInAppHistory(CONSUMER_ID, null, 2))
                .willReturn(List.of(task));
        given(sourceRegistry.readContext(
                NotificationSourceDomain.PICKUP,
                NotificationResourceType.PICKUP_RESERVATION,
                31L,
                3L,
                CONSUMER_ID
        )).willReturn(context(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE, null));

        var page = service.getHistory(CONSUMER_ID, null, 1);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.notificationId()).isEqualTo("103");
            assertThat(item.action()).isNull();
        });
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void sourceAdapterExceptionKeepsDeliveredItemAndOmitsAction() {
        HistoryTask task = historyTask(103L, OCCURRED_AT);
        given(repository.findDeliveredInAppHistory(CONSUMER_ID, null, 2))
                .willReturn(List.of(task));
        given(sourceRegistry.readContext(
                NotificationSourceDomain.PICKUP,
                NotificationResourceType.PICKUP_RESERVATION,
                31L,
                3L,
                CONSUMER_ID
        )).willThrow(new IllegalStateException("source temporarily unavailable"));

        assertThat(service.getHistory(CONSUMER_ID, null, 1).items())
                .singleElement()
                .extracting(item -> item.action())
                .isNull();
    }

    @Test
    void recipientRelationMismatchNeverReturnsAction() {
        HistoryTask task = historyTask(103L, OCCURRED_AT);
        given(repository.findDeliveredInAppHistory(CONSUMER_ID, null, 2))
                .willReturn(List.of(task));
        given(sourceRegistry.readContext(
                NotificationSourceDomain.PICKUP,
                NotificationResourceType.PICKUP_RESERVATION,
                31L,
                3L,
                CONSUMER_ID
        )).willReturn(new NotificationSourceContextV1(
                NotificationSourceReadResult.FOUND,
                3L,
                8L,
                "CONFIRMED",
                "미리윰",
                null,
                null,
                null,
                NotificationActionType.PICKUP_RESERVATION_DETAIL,
                NotificationResourceType.PICKUP_RESERVATION,
                "31",
                NotificationActionAvailability.AVAILABLE
        ));

        assertThat(service.getHistory(CONSUMER_ID, null, 1).items())
                .singleElement()
                .extracting(item -> item.action())
                .isNull();
    }

    @Test
    void missingCursorSecretFailsClosedEvenForEmptyFirstPage() {
        NotificationHistoryService unavailableService = new NotificationHistoryService(
                repository,
                new NotificationCursorCodec(new NotificationHistorySettings("")),
                sourceRegistry
        );

        assertThatThrownBy(() -> unavailableService.getHistory(CONSUMER_ID, null, 20))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
        verifyNoInteractions(repository);
    }

    private static HistoryTask historyTask(long notificationId, Instant occurredAt) {
        return new HistoryTask(
                notificationId,
                NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                7L,
                NotificationResourceType.PICKUP_RESERVATION,
                31L,
                3L,
                "픽업 예약이 확정되었습니다.",
                occurredAt,
                occurredAt.plusSeconds(1),
                occurredAt.plusSeconds(2)
        );
    }

    private static NotificationSourceContextV1 foundAction() {
        return context(NotificationSourceReadResult.FOUND,
                NotificationActionAvailability.AVAILABLE);
    }

    private static NotificationSourceContextV1 context(
            NotificationSourceReadResult result,
            NotificationActionAvailability availability
    ) {
        boolean withAction = availability != null;
        return new NotificationSourceContextV1(
                result,
                withAction ? 3L : 0L,
                withAction ? 7L : 0L,
                withAction ? "CONFIRMED" : null,
                withAction ? "미리윰" : null,
                null,
                null,
                withAction ? OffsetDateTime.ofInstant(OCCURRED_AT.plusSeconds(600), ZoneOffset.UTC) : null,
                withAction ? NotificationActionType.PICKUP_RESERVATION_DETAIL : null,
                withAction ? NotificationResourceType.PICKUP_RESERVATION : null,
                withAction ? "31" : null,
                availability
        );
    }
}
