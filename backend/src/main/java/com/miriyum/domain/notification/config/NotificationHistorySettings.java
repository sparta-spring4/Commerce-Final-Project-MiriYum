package com.miriyum.domain.notification.config;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Notification history cursor에만 사용하는 독립적인 서명 secret 경계다. */
@Component
public class NotificationHistorySettings {

    private static final int MINIMUM_SECRET_LENGTH = 32;

    private final String cursorSecret;

    public NotificationHistorySettings(
            @Value("${miriyum.notification.history.cursor-secret:}") String cursorSecret
    ) {
        this.cursorSecret = cursorSecret;
    }

    /**
     * 설정이 누락되거나 충분히 길지 않으면 cursor endpoint를 fail-closed로 유지한다.
     *
     * @return HMAC 입력에 사용할 UTF-8 key
     */
    public byte[] requireCursorKey() {
        if (cursorSecret == null || cursorSecret.length() < MINIMUM_SECRET_LENGTH) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return cursorSecret.getBytes(StandardCharsets.UTF_8);
    }
}
