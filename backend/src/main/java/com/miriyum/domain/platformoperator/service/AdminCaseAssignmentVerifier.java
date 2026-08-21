package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import java.util.Optional;

/** 후속 업무 도메인이 중앙 사건 배정의 담당자·version·만료를 검증하는 공개 계약이다. */
public interface AdminCaseAssignmentVerifier {
    void verify(AdminCaseAssignmentRequest request);

    /** 현재 사건 version에 결속된 만료 전 담당자를 공개한다. */
    Optional<Long> findActiveOperator(AdminCaseType caseType, String caseId, long caseVersion);
}
