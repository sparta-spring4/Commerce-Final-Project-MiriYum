package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationTaskReceipt;
import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.domain.notification.repository.NotificationChannelAttemptRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.StoredTask;
import com.miriyum.domain.notification.repository.NotificationTaskTransitionAuditRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationTaskRecorderRuntime implements NotificationTaskRecorder {

    private final NotificationTaskRepository taskRepository;
    private final NotificationChannelAttemptRepository attemptRepository;
    private final NotificationTaskTransitionAuditRepository auditRepository;

    public NotificationTaskRecorderRuntime(
            NotificationTaskRepository taskRepository,
            NotificationChannelAttemptRepository attemptRepository,
            NotificationTaskTransitionAuditRepository auditRepository
    ) {
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.auditRepository = auditRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationTaskReceipt record(NotificationSourceEventV1 event) {
        Objects.requireNonNull(event, "event must not be null");
        String fingerprint = NotificationPayloadFingerprint.of(event);
        StoredTask stored = taskRepository.insertOrFind(event, fingerprint);
        if (!stored.payloadFingerprint().equals(fingerprint)) {
            throw new ServiceException(NotificationErrorCode.SOURCE_EVENT_CONFLICT);
        }
        if (stored.inserted()) {
            attemptRepository.insertInitialInApp(stored.notificationId());
            auditRepository.insertInitialPending(stored.notificationId(), event.correlationId());
        }
        return new NotificationTaskReceipt(
                Long.toString(stored.notificationId()),
                !stored.inserted()
        );
    }
}
