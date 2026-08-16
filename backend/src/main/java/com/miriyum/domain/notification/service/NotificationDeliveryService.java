package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.config.NotificationSettings.RuntimePolicy;
import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.entity.NotificationTaskStatus;
import com.miriyum.domain.notification.repository.NotificationChannelAttemptRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.DeliveryCompletion;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.DueTask;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.LeasedTask;
import com.miriyum.domain.notification.repository.NotificationTaskTransitionAuditRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 임대한 알림 작업을 최신 원 상태에 수렴시켜 IN_APP 채널에 전달한다.
 */
@Service
public class NotificationDeliveryService {

    private static final String SOURCE_SUPERSEDED = "SOURCE_SUPERSEDED";
    private static final String RECIPIENT_NOT_ELIGIBLE = "RECIPIENT_NOT_ELIGIBLE";
    private static final String SOURCE_TEMPORARILY_UNAVAILABLE =
            "SOURCE_TEMPORARILY_UNAVAILABLE";
    private static final String SOURCE_RETRY_EXHAUSTED = "SOURCE_RETRY_EXHAUSTED";
    private static final String SOURCE_CONTEXT_INVALID = "SOURCE_CONTEXT_INVALID";
    private static final String TASK_EXPIRED = "TASK_EXPIRED";
    private static final String WAITING_RESERVATION_CONVERTING =
            "WAITING_RESERVATION_CONVERTING";

    private final NotificationTaskRepository taskRepository;
    private final NotificationChannelAttemptRepository channelAttemptRepository;
    private final NotificationTaskTransitionAuditRepository transitionAuditRepository;
    private final NotificationDatabaseClock databaseClock;
    private final NotificationSourceRegistry sourceRegistry;
    private final NotificationTitleRenderer titleRenderer;
    private final TransactionTemplate transactions;

    public NotificationDeliveryService(
            NotificationTaskRepository taskRepository,
            NotificationChannelAttemptRepository channelAttemptRepository,
            NotificationTaskTransitionAuditRepository transitionAuditRepository,
            NotificationDatabaseClock databaseClock,
            NotificationSourceRegistry sourceRegistry,
            NotificationTitleRenderer titleRenderer,
            TransactionTemplate transactions
    ) {
        this.taskRepository = taskRepository;
        this.channelAttemptRepository = channelAttemptRepository;
        this.transitionAuditRepository = transitionAuditRepository;
        this.databaseClock = databaseClock;
        this.sourceRegistry = sourceRegistry;
        this.titleRenderer = titleRenderer;
        this.transactions = transactions;
    }

    /**
     * 현재 시각에 실행 가능한 작업을 정책의 batch 상한까지 처리한다.
     *
     * @param policy 검증 완료된 버전 정책
     * @return 최종 상태로 수렴한 작업 수
     */
    public int deliverDueBatch(RuntimePolicy policy) {
        int converged = 0;
        for (int index = 0; index < policy.batchSize(); index++) {
            Optional<LeasedTask> claimed = claimNext(policy, databaseClock.now());
            if (claimed.isEmpty()) {
                break;
            }
            if (deliver(claimed.orElseThrow(), policy)) {
                converged++;
            }
        }
        return converged;
    }

    private Optional<LeasedTask> claimNext(RuntimePolicy policy, Instant dueAt) {
        return transactions.execute(status -> taskRepository.findNextDueForUpdate(dueAt)
                .map(task -> claim(task, policy)));
    }

    private LeasedTask claim(DueTask task, RuntimePolicy policy) {
        LeasedTask leased = taskRepository.claim(
                task,
                policy.workerId(),
                UUID.randomUUID().toString(),
                policy.leaseDuration().toMillis()
        );
        channelAttemptRepository.markProcessing(task.notificationId());
        if (task.leaseRecovery()) {
            transitionAuditRepository.insert(
                    task.notificationId(),
                    NotificationTaskStatus.PENDING,
                    NotificationTaskStatus.PENDING,
                    auditReason("LEASE_RECOVERED", policy),
                    task.correlationId()
            );
        }
        return leased;
    }

    private boolean deliver(LeasedTask task, RuntimePolicy policy) {
        if (task.sourceDomain()
                != com.miriyum.domain.notification.dto.source.NotificationSourceDomain.WAITING) {
            return readAndDeliver(task, policy, false);
        }
        try {
            Boolean converged = transactions.execute(
                    status -> readAndDeliver(task, policy, true));
            return Boolean.TRUE.equals(converged);
        } catch (SourceReadFailure unavailable) {
            return retryOrFail(task, policy);
        }
    }

    private boolean readAndDeliver(
            LeasedTask task,
            RuntimePolicy policy,
            boolean lockedWaitingDelivery
    ) {
        NotificationSourceContextV1 context;
        try {
            context = lockedWaitingDelivery
                    ? sourceRegistry.readContextForDelivery(
                            task.sourceDomain(),
                            task.resourceType(),
                            task.resourceId(),
                            task.resourceVersion(),
                            task.recipientAccountId())
                    : sourceRegistry.readContext(
                            task.sourceDomain(),
                            task.resourceType(),
                            task.resourceId(),
                            task.resourceVersion(),
                            task.recipientAccountId());
        } catch (RuntimeException sourceFailure) {
            if (lockedWaitingDelivery) {
                throw new SourceReadFailure(sourceFailure);
            }
            context = unavailable();
        }
        if (context == null) {
            context = unavailable();
        }
        if (isExpired(task.expiresAt(), databaseClock.now())) {
            return cancel(task, TASK_EXPIRED, policy);
        }
        return switch (context.result()) {
            case FOUND -> deliverFound(task, context, policy);
            case SUPERSEDED -> cancel(task, SOURCE_SUPERSEDED, policy);
            case NOT_ELIGIBLE -> cancel(task, RECIPIENT_NOT_ELIGIBLE, policy);
            case TEMPORARILY_UNAVAILABLE -> isWaitingConversionHold(task, context)
                    ? holdWaitingConversion(task, policy)
                    : retryOrFail(task, policy);
        };
    }

    private static boolean isWaitingConversionHold(
            LeasedTask task,
            NotificationSourceContextV1 context
    ) {
        return task.sourceDomain()
                == com.miriyum.domain.notification.dto.source.NotificationSourceDomain.WAITING
                && task.purpose()
                == com.miriyum.domain.notification.dto.source.NotificationPurpose.WAITING_ENTRY_IMMINENT
                && context.resourceVersion() == task.resourceVersion()
                && context.recipientRelationVersion() == task.recipientRelationVersion()
                && "RESERVATION_CONVERTING".equals(context.sourceState());
    }

    private boolean holdWaitingConversion(LeasedTask task, RuntimePolicy policy) {
        var delay = policy.retryDelay(1);
        transactions.execute(status -> {
            if (!taskRepository.scheduleWaitingConversionHold(
                    task, WAITING_RESERVATION_CONVERTING, delay.toMillis())) {
                return false;
            }
            channelAttemptRepository.markWaitingHoldPending(
                    task.notificationId(), WAITING_RESERVATION_CONVERTING);
            transitionAuditRepository.insert(
                    task.notificationId(),
                    NotificationTaskStatus.PENDING,
                    NotificationTaskStatus.PENDING,
                    auditReason(WAITING_RESERVATION_CONVERTING, policy),
                    task.correlationId()
            );
            return true;
        });
        return false;
    }

    private boolean deliverFound(
            LeasedTask task,
            NotificationSourceContextV1 context,
            RuntimePolicy policy
    ) {
        if (context.recipientRelationVersion() != task.recipientRelationVersion()) {
            return cancel(task, RECIPIENT_NOT_ELIGIBLE, policy);
        }
        if (context.resourceVersion() != task.resourceVersion()
                || !Objects.equals(context.sourceState(), task.sourceState())) {
            return cancel(task, SOURCE_SUPERSEDED, policy);
        }
        Instant now = databaseClock.now();
        if (context.expiresAt() != null
                && !now.isBefore(context.expiresAt().toInstant())) {
            return cancel(task, SOURCE_SUPERSEDED, policy);
        }
        String title;
        try {
            title = titleRenderer.render(task.purpose(), context);
        } catch (RuntimeException invalidContext) {
            return fail(task, SOURCE_CONTEXT_INVALID, policy);
        }
        Instant sourceExpiresAt = context.expiresAt() == null
                ? null
                : context.expiresAt().toInstant();
        return completeDelivery(task, title, sourceExpiresAt, policy);
    }

    private boolean completeDelivery(
            LeasedTask task,
            String title,
            Instant sourceExpiresAt,
            RuntimePolicy policy
    ) {
        Boolean updated = transactions.execute(status -> {
            Optional<DeliveryCompletion> completion =
                    taskRepository.completeDelivery(task, title, sourceExpiresAt);
            if (completion.isEmpty()) {
                return false;
            }
            DeliveryCompletion outcome = completion.orElseThrow();
            if (outcome.status() == NotificationTaskStatus.DELIVERED) {
                channelAttemptRepository.markDelivered(task.notificationId());
            } else if (outcome.status() == NotificationTaskStatus.CANCELLED) {
                channelAttemptRepository.markCancelled(task.notificationId(), outcome.reason());
            } else {
                throw new IllegalStateException("unexpected delivery completion status");
            }
            transitionAuditRepository.insert(
                    task.notificationId(),
                    NotificationTaskStatus.PENDING,
                    outcome.status(),
                    auditReason(outcome.status() == NotificationTaskStatus.DELIVERED
                            ? "IN_APP_DELIVERED"
                            : outcome.reason(), policy),
                    task.correlationId()
            );
            return true;
        });
        return Boolean.TRUE.equals(updated);
    }

    private boolean cancel(LeasedTask task, String reason, RuntimePolicy policy) {
        Boolean updated = transactions.execute(status -> {
            if (!taskRepository.markCancelled(task, reason)) {
                return false;
            }
            channelAttemptRepository.markCancelled(task.notificationId(), reason);
            transitionAuditRepository.insert(
                    task.notificationId(),
                    NotificationTaskStatus.PENDING,
                    NotificationTaskStatus.CANCELLED,
                    auditReason(reason, policy),
                    task.correlationId()
            );
            return true;
        });
        return Boolean.TRUE.equals(updated);
    }

    private boolean retryOrFail(LeasedTask task, RuntimePolicy policy) {
        Instant now = databaseClock.now();
        if (task.attemptCount() >= policy.maxAttempts()) {
            return fail(task, SOURCE_RETRY_EXHAUSTED, policy);
        }
        var retryDelay = policy.retryDelay(task.attemptCount());
        Instant nextAttemptAt = now.plus(retryDelay);
        if (task.expiresAt() != null && !nextAttemptAt.isBefore(task.expiresAt())) {
            return cancel(task, TASK_EXPIRED, policy);
        }
        Boolean scheduled = transactions.execute(status -> {
            if (!taskRepository.scheduleRetry(
                    task,
                    SOURCE_TEMPORARILY_UNAVAILABLE,
                    retryDelay.toMillis())) {
                return false;
            }
            channelAttemptRepository.markRetryPending(
                    task.notificationId(), SOURCE_TEMPORARILY_UNAVAILABLE);
            transitionAuditRepository.insert(
                    task.notificationId(),
                    NotificationTaskStatus.PENDING,
                    NotificationTaskStatus.PENDING,
                    auditReason(SOURCE_TEMPORARILY_UNAVAILABLE, policy),
                    task.correlationId()
            );
            return true;
        });
        if (!Boolean.TRUE.equals(scheduled)) {
            return cancel(task, TASK_EXPIRED, policy);
        }
        return false;
    }

    private boolean fail(LeasedTask task, String reason, RuntimePolicy policy) {
        Boolean updated = transactions.execute(status -> {
            if (!taskRepository.markFailed(task, reason)) {
                return false;
            }
            channelAttemptRepository.markFailed(task.notificationId(), reason);
            transitionAuditRepository.insert(
                    task.notificationId(),
                    NotificationTaskStatus.PENDING,
                    NotificationTaskStatus.FAILED,
                    auditReason(reason, policy),
                    task.correlationId()
            );
            return true;
        });
        return Boolean.TRUE.equals(updated);
    }

    private static NotificationSourceContextV1 unavailable() {
        return new NotificationSourceContextV1(
                NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE,
                0L,
                0L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static String auditReason(String reason, RuntimePolicy policy) {
        return reason + "@" + policy.policyVersion();
    }

    private static boolean isExpired(Instant expiresAt, Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    private static final class SourceReadFailure extends RuntimeException {

        private SourceReadFailure(RuntimeException cause) {
            super(cause);
        }
    }
}
