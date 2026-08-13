package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.dto.response.NotificationActionResponse;
import com.miriyum.domain.notification.dto.response.NotificationHistoryItemResponse;
import com.miriyum.domain.notification.dto.response.NotificationHistoryPageResponse;
import com.miriyum.domain.notification.dto.response.NotificationResourceResponse;
import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.entity.NotificationActionAvailability;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.HistoryBoundary;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.HistoryTask;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전달 성공한 소비자 IN_APP 알림 이력을 공개 계약 형태로 조립한다. */
@Service
public class NotificationHistoryService {

    private final NotificationTaskRepository taskRepository;
    private final NotificationCursorCodec cursorCodec;
    private final NotificationSourceRegistry sourceRegistry;

    public NotificationHistoryService(
            NotificationTaskRepository taskRepository,
            NotificationCursorCodec cursorCodec,
            NotificationSourceRegistry sourceRegistry
    ) {
        this.taskRepository = taskRepository;
        this.cursorCodec = cursorCodec;
        this.sourceRegistry = sourceRegistry;
    }

    @Transactional(readOnly = true)
    public NotificationHistoryPageResponse getHistory(
            long consumerAccountId,
            String cursor,
            int size
    ) {
        if (consumerAccountId <= 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("notification history arguments are invalid");
        }
        cursorCodec.requireAvailable();
        NotificationCursorCodec.Boundary decoded = cursor == null
                ? null
                : cursorCodec.decode(consumerAccountId, cursor);
        HistoryBoundary boundary = decoded == null
                ? null
                : new HistoryBoundary(decoded.occurredAt(), decoded.notificationId());
        List<HistoryTask> rows = readDeliveredInAppHistory(
                consumerAccountId, boundary, size + 1);
        boolean hasNext = rows.size() > size;
        List<HistoryTask> visible = hasNext ? rows.subList(0, size) : rows;
        List<NotificationHistoryItemResponse> items = new ArrayList<>(visible.size());
        for (HistoryTask task : visible) {
            items.add(toResponse(consumerAccountId, task));
        }
        String nextCursor = null;
        if (hasNext) {
            HistoryTask last = visible.getLast();
            nextCursor = cursorCodec.encode(
                    consumerAccountId,
                    new NotificationCursorCodec.Boundary(
                            last.occurredAt(), last.notificationId())
            );
        }
        return new NotificationHistoryPageResponse(List.copyOf(items), hasNext, nextCursor);
    }

    private List<HistoryTask> readDeliveredInAppHistory(
            long consumerAccountId,
            HistoryBoundary boundary,
            int limit
    ) {
        try {
            return taskRepository.findDeliveredInAppHistory(
                    consumerAccountId, boundary, limit);
        } catch (TransientDataAccessException | DataAccessResourceFailureException unavailable) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private NotificationHistoryItemResponse toResponse(
            long consumerAccountId,
            HistoryTask task
    ) {
        return new NotificationHistoryItemResponse(
                Long.toString(task.notificationId()),
                task.purpose(),
                task.title(),
                new NotificationResourceResponse(
                        task.resourceType(), Long.toString(task.resourceId())),
                utc(task.occurredAt()),
                utc(task.createdAt()),
                utc(task.deliveredAt()),
                readAction(consumerAccountId, task)
        );
    }

    private NotificationActionResponse readAction(long consumerAccountId, HistoryTask task) {
        NotificationSourceContextV1 context;
        try {
            context = sourceRegistry.readContext(
                    task.sourceDomain(),
                    task.resourceType(),
                    task.resourceId(),
                    task.resourceVersion(),
                    consumerAccountId
            );
        } catch (RuntimeException sourceFailure) {
            return null;
        }
        if (context == null
                || (context.result() != NotificationSourceReadResult.FOUND
                    && context.result() != NotificationSourceReadResult.SUPERSEDED)
                || context.recipientRelationVersion() != task.recipientRelationVersion()
                || context.actionType() == null) {
            return null;
        }
        NotificationActionAvailability availability =
                context.result() == NotificationSourceReadResult.SUPERSEDED
                        ? NotificationActionAvailability.SUPERSEDED
                        : context.actionAvailability();
        return new NotificationActionResponse(
                context.actionType(),
                new NotificationResourceResponse(
                        context.actionResourceType(), context.actionResourceId()),
                availability,
                context.expiresAt()
        );
    }

    private static OffsetDateTime utc(Instant value) {
        if (value == null) {
            throw new IllegalStateException("delivered notification history timestamp is missing");
        }
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
