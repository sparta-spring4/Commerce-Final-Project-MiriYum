package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 운영자 목록 필터와 검증·복호화된 복합 keyset cursor다. */
public record WaitingTeamListQuery(
        WaitingTeamStatus status,
        Long afterQueueSequence,
        Long afterWaitingTeamId,
        int size
) {

    private static final int DEFAULT_SIZE = 20;
    private static final Pattern CURSOR_PAYLOAD = Pattern.compile("([1-9][0-9]*):([1-9][0-9]*)");

    /** HTTP query 값을 공개 오류만 남도록 검증하고 정규화한다. */
    public static WaitingTeamListQuery from(String rawStatus, String rawCursor, Integer rawSize) {
        int size = rawSize == null ? DEFAULT_SIZE : rawSize;
        if (size < 1 || size > 100) {
            throw invalid();
        }
        WaitingTeamStatus status = parseStatus(rawStatus);
        if (rawCursor == null) {
            return new WaitingTeamListQuery(status, null, null, size);
        }
        long[] cursor = decode(rawCursor);
        return new WaitingTeamListQuery(status, cursor[0], cursor[1], size);
    }

    /** 마지막 공개 목록 항목을 URL-safe opaque cursor로 인코딩한다. */
    public static String encode(long queueSequence, long waitingTeamId) {
        String payload = queueSequence + ":" + waitingTeamId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
    }

    private static WaitingTeamStatus parseStatus(String rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        try {
            return WaitingTeamStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static long[] decode(String rawCursor) {
        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.US_ASCII);
            Matcher matcher = CURSOR_PAYLOAD.matcher(payload);
            if (!matcher.matches()) {
                throw invalid();
            }
            return new long[]{Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))};
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static ServiceException invalid() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
