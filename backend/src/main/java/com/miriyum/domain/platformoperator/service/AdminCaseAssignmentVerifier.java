package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;

/** 후속 업무 도메인이 중앙 사건 배정의 담당자·version·만료를 검증하는 공개 계약이다. */
public interface AdminCaseAssignmentVerifier {
    void verify(AdminCaseAssignmentRequest request);
}
