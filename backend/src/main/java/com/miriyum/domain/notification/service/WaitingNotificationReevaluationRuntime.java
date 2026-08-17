package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.entity.NotificationTaskStatus;
import com.miriyum.domain.notification.repository.NotificationChannelAttemptRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.PendingWaitingEntryTask;
import com.miriyum.domain.notification.repository.NotificationTaskTransitionAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitingNotificationReevaluationRuntime
        implements WaitingNotificationReevaluationService {

    private final NotificationTaskRepository taskRepository;
    private final NotificationChannelAttemptRepository attemptRepository;
    private final NotificationTaskTransitionAuditRepository auditRepository;

    public WaitingNotificationReevaluationRuntime(
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
    public void reevaluate(
            long waitingTeamId,
            long waitingStatusEventId,
            long eventSequence
    ) {
        if (waitingTeamId <= 0 || waitingStatusEventId <= 0 || eventSequence <= 0) {
            throw new IllegalArgumentException("waiting reevaluation identifiers must be positive");
        }
        String reason = "WAITING_REEVALUATION:"
                + waitingStatusEventId + ':' + eventSequence;
        for (PendingWaitingEntryTask task
                : taskRepository.findPendingWaitingEntryTasksForUpdate(waitingTeamId)) {
            if (!taskRepository.reevaluateWaitingEntryTask(task)) {
                continue;
            }
            attemptRepository.markReevaluationPending(task.notificationId(), task.claimed());
            auditRepository.insert(
                    task.notificationId(),
                    NotificationTaskStatus.PENDING,
                    NotificationTaskStatus.PENDING,
                    reason,
                    task.correlationId()
            );
        }
    }
}
