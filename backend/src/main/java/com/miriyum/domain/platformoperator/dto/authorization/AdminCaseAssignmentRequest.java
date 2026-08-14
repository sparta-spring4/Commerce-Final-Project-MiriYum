package com.miriyum.domain.platformoperator.dto.authorization;

import com.miriyum.domain.platformoperator.enums.AdminCaseType;

/** 다른 도메인 Entity 없이 사건 담당자와 version을 검증하는 scalar 요청이다. */
public record AdminCaseAssignmentRequest(
        AdminCaseType caseType,
        String caseId,
        long caseVersion,
        long operatorId
) {
    public AdminCaseAssignmentRequest {
        if (caseType == null || caseId == null || caseId.isBlank() || caseVersion < 1 || operatorId < 1) {
            throw new IllegalArgumentException("valid case assignment fields are required");
        }
    }
}
