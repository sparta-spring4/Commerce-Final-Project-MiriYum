package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 승인된 표시명만 사용하는 IN_APP 제목 renderer다.
 */
@Component
public class NotificationTitleRenderer {

    public String render(NotificationPurpose purpose, NotificationSourceContextV1 context) {
        String store = context.storeDisplayName();
        if (store == null || store.isBlank()) {
            throw new IllegalArgumentException("storeDisplayName is required for notification title");
        }
        String title = switch (purpose) {
            case RESERVATION_CONFIRMED -> store + " 예약이 확정되었습니다.";
            case RESERVATION_CHANGED -> store + " 예약이 변경되었습니다.";
            case RESERVATION_REJECTED -> store + " 예약을 확정하지 못했습니다.";
            case RESERVATION_CANCELLED -> store + " 예약이 취소되었습니다.";
            case RESERVATION_EXPIRED -> store + " 예약 요청이 만료되었습니다.";
            case RESERVATION_VISIT_REMINDER -> store + " 방문 예정 시간을 확인해 주세요.";
            case RESERVATION_COORDINATION_REQUIRED -> store + " 예약 확인이 필요합니다.";
            case PICKUP_RESERVATION_CONFIRMED -> store + " 픽업 예약이 확정되었습니다.";
            case PICKUP_RESERVATION_CANCELLED -> store + " 픽업 예약이 취소되었습니다.";
            case MENU_HOLD_FULFILLMENT_AT_RISK -> store + " 메뉴 준비 상태를 확인해 주세요.";
            case MENU_SUBSTITUTION_PROPOSED -> store + " 대체 메뉴를 확인해 주세요.";
            case MENU_SUBSTITUTION_ACCEPTED -> store + " 대체 메뉴가 반영되었습니다.";
            case MENU_SUBSTITUTION_REJECTED -> store + " 대체 메뉴가 거절되었습니다.";
            case MENU_SUBSTITUTION_EXPIRED -> store + " 대체 메뉴 제안이 만료되었습니다.";
        };
        return truncate(title, 100);
    }

    private static String truncate(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        return value.codePoints()
                .limit(maximumCodePoints)
                .mapToObj(Character::toString)
                .collect(Collectors.joining());
    }
}
