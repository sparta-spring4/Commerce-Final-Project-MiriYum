package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationTitleRendererTest {

    private final NotificationTitleRenderer renderer = new NotificationTitleRenderer();

    @Test
    @DisplayName("예약 방문 완료와 노쇼 제목은 종결 상태만 안전하게 설명한다")
    void terminalReservationTitlesOnlyDescribeTheConfirmedVisitState() {
        NotificationSourceContextV1 context = new NotificationSourceContextV1(
                NotificationSourceReadResult.FOUND,
                2L,
                1L,
                "FULFILLED",
                "미리윰 식당",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(renderer.render(
                NotificationPurpose.RESERVATION_VISIT_COMPLETED, context))
                .isEqualTo("미리윰 식당 방문이 완료되었습니다.");
        assertThat(renderer.render(NotificationPurpose.RESERVATION_NO_SHOW, context))
                .isEqualTo("미리윰 식당 예약이 노쇼 처리되었습니다.");
    }
}
