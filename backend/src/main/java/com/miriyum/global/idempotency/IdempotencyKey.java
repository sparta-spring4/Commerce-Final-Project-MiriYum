package com.miriyum.global.idempotency;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 검증·정규화된 {@code Idempotency-Key} 값이다.
 *
 * <p>쓰기 트랜잭션 진입 전(도메인 컨트롤러/전처리)에 {@link #parse(String)}로 검증·정규화한다.
 * 표준 하이픈 UUID 36자만 허용하고 소문자로 정규화한다.</p>
 */
public final class IdempotencyKey {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private final String value;

    private IdempotencyKey(String value) {
        this.value = value;
    }

    /**
     * 헤더 값을 검증·정규화한다.
     *
     * @param raw 요청 헤더의 원본 값(없으면 {@code null})
     * @return 검증 후 소문자로 정규화된 멱등 키
     * @throws ServiceException 누락 시 {@code COMMON_003}, 형식 오류 시 {@code COMMON_004}
     */
    public static IdempotencyKey parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (raw.length() != 36 || !UUID_PATTERN.matcher(raw).matches()) {
            throw new ServiceException(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
        return new IdempotencyKey(raw.toLowerCase(Locale.ROOT));
    }

    public String value() {
        return value;
    }
}
