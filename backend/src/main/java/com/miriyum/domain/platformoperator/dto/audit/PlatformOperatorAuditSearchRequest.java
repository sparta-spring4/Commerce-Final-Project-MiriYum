package com.miriyum.domain.platformoperator.dto.audit;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

public record PlatformOperatorAuditSearchRequest(
        int page,
        int size,
        String source,
        PlatformOperatorAuditAction action,
        PlatformOperatorAuditOutcome outcome,
        String actorOperatorId,
        String targetType,
        String targetId,
        Instant occurredFrom,
        Instant occurredTo,
        String originalEventKey
) {
    private static final Set<String> SOURCES = Set.of("AUTH", "ADMIN");
    private static final Pattern PUBLIC_ID = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$");
    private static final Pattern ORIGINAL_EVENT_KEY = Pattern.compile(
            "^(?:AUTH|ADMIN):[1-9][0-9]{0,18}$");

    public PlatformOperatorAuditSearchRequest {
        if (page < 0 || size < 1 || size > 100) {
            throw validationFailed();
        }
        if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw validationFailed();
        }
        if (source != null && !SOURCES.contains(source)) {
            throw validationFailed();
        }
        if (!matchesWhenPresent(PUBLIC_ID, actorOperatorId)
                || !matchesWhenPresent(PUBLIC_ID, targetId)
                || !matchesWhenPresent(ORIGINAL_EVENT_KEY, originalEventKey)) {
            throw validationFailed();
        }
    }

    private static boolean matchesWhenPresent(Pattern pattern, String value) {
        return value == null || pattern.matcher(value).matches();
    }

    private static ServiceException validationFailed() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
