package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.port.MenuHoldNotificationSource;
import com.miriyum.domain.notification.port.PickupNotificationSource;
import com.miriyum.domain.notification.port.ReservationNotificationSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 원 사건 자원 소유권에 따라 Notification 공개 조회 포트를 선택한다.
 */
@Component
public class NotificationSourceRegistry {

    private final Optional<ReservationNotificationSource> reservationSource;
    private final Optional<MenuHoldNotificationSource> menuHoldSource;
    private final Optional<PickupNotificationSource> pickupSource;

    public NotificationSourceRegistry(
            Optional<ReservationNotificationSource> reservationSource,
            Optional<MenuHoldNotificationSource> menuHoldSource,
            Optional<PickupNotificationSource> pickupSource
    ) {
        this.reservationSource = reservationSource;
        this.menuHoldSource = menuHoldSource;
        this.pickupSource = pickupSource;
    }

    public NotificationSourceContextV1 readContext(
            NotificationSourceDomain sourceDomain,
            NotificationResourceType resourceType,
            long resourceId,
            long resourceVersion,
            long recipientAccountId
    ) {
        String resource = Long.toString(resourceId);
        String recipient = Long.toString(recipientAccountId);
        return switch (sourceDomain) {
            case RESERVATION -> {
                requireResource(resourceType, NotificationResourceType.RESERVATION);
                yield reservationSource
                        .map(source -> source.readContext(resource, resourceVersion, recipient))
                        .orElseGet(NotificationSourceRegistry::temporarilyUnavailable);
            }
            case MENU_HOLD -> {
                if (resourceType != NotificationResourceType.MENU_HOLD
                        && resourceType != NotificationResourceType.MENU_SUBSTITUTION_PROPOSAL) {
                    throw new IllegalStateException("MenuHold does not own notification resource");
                }
                yield menuHoldSource
                        .map(source -> source.readContext(
                                resourceType, resource, resourceVersion, recipient))
                        .orElseGet(NotificationSourceRegistry::temporarilyUnavailable);
            }
            case PICKUP -> {
                requireResource(resourceType, NotificationResourceType.PICKUP_RESERVATION);
                yield pickupSource
                        .map(source -> source.readContext(resource, resourceVersion, recipient))
                        .orElseGet(NotificationSourceRegistry::temporarilyUnavailable);
            }
        };
    }

    private static void requireResource(
            NotificationResourceType actual,
            NotificationResourceType expected
    ) {
        if (actual != expected) {
            throw new IllegalStateException("source domain does not own notification resource");
        }
    }

    private static NotificationSourceContextV1 temporarilyUnavailable() {
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
}
