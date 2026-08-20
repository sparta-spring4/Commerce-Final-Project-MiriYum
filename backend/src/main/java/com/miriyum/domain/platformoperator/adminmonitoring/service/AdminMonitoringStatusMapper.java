package com.miriyum.domain.platformoperator.adminmonitoring.service;

import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Source;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "miriyum.admin-monitoring", name = "enabled", havingValue = "true")
public class AdminMonitoringStatusMapper {

    public LifecycleStatus lifecycle(Source source, String sourceStatus) {
        if (source == null || sourceStatus == null) {
            throw new IllegalArgumentException("primary source status is required");
        }
        return switch (source) {
            case RESERVATION_HOLD -> switch (sourceStatus) {
                case "ACTIVE", "RECONCILIATION_REQUIRED" -> LifecycleStatus.PENDING;
                case "CONFIRMED" -> LifecycleStatus.CONFIRMED;
                case "RELEASED", "EXPIRED" -> LifecycleStatus.CANCELLED;
                default -> throw unknown(source, sourceStatus);
            };
            case RESERVATION -> switch (sourceStatus) {
                case "CONFIRMED" -> LifecycleStatus.CONFIRMED;
                case "FULFILLED" -> LifecycleStatus.COMPLETED;
                case "CANCELLED" -> LifecycleStatus.CANCELLED;
                case "NO_SHOW" -> LifecycleStatus.NO_SHOW;
                default -> throw unknown(source, sourceStatus);
            };
            case WAITING -> switch (sourceStatus) {
                case "WAITING", "CALLED", "RESERVATION_CONVERTING" -> LifecycleStatus.PENDING;
                case "ARRIVED" -> LifecycleStatus.CONFIRMED;
                case "CHECKED_IN", "RESERVATION_CONVERTED" -> LifecycleStatus.CHECKED_IN;
                case "CLOSED_BY_STORE" -> LifecycleStatus.COMPLETED;
                case "CANCELLED" -> LifecycleStatus.CANCELLED;
                case "NO_SHOW" -> LifecycleStatus.NO_SHOW;
                default -> throw unknown(source, sourceStatus);
            };
            case MENU_HOLD, PAYMENT -> throw new IllegalArgumentException(
                    "linked sources cannot determine lifecycle");
        };
    }

    public LifecycleStatus reservationLifecycle(String sourceStatus, Set<String> eventTypes) {
        LifecycleStatus lifecycle = lifecycle(Source.RESERVATION, sourceStatus);
        if (lifecycle == LifecycleStatus.CONFIRMED
                && eventTypes != null
                && eventTypes.contains("CHECKED_IN")) {
            return LifecycleStatus.CHECKED_IN;
        }
        return lifecycle;
    }

    private static IllegalArgumentException unknown(Source source, String status) {
        return new IllegalArgumentException("unknown " + source + " status: " + status);
    }
}
