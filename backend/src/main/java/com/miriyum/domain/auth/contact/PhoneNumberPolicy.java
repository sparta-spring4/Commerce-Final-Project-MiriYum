package com.miriyum.domain.auth.contact;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * 1차 MVP 연락처 입력을 정규화하고 허용 범위를 검증한다.
 */
@Component
public class PhoneNumberPolicy {

    private static final String MVP_PHONE_PATTERN = "010[0-9]{8}";

    public String normalize(String rawPhoneNumber) {
        if (rawPhoneNumber == null) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }

        String normalized = rawPhoneNumber.replaceAll("[\\s-]", "");
        if (!normalized.matches(MVP_PHONE_PATTERN)) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }
}
