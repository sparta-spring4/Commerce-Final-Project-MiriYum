package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.CasePageResponse;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.CaseResponse;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSupportCaseQueryService {
    private final MemberSupportAuthorizationService authorization;
    private final MemberSupportCaseRepository cases;

    public MemberSupportCaseQueryService(MemberSupportAuthorizationService authorization,
                                         MemberSupportCaseRepository cases) {
        this.authorization = authorization;
        this.cases = cases;
    }

    @Transactional(readOnly = true)
    public CaseResponse get(PlatformOperatorPrincipal principal, String caseId) {
        authorization.requirePermission(principal, PlatformOperatorPermission.MEMBER_READ_MINIMAL);
        return cases.findByPublicId(caseId).map(this::response)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public CasePageResponse list(PlatformOperatorPrincipal principal, int page, int size) {
        authorization.requirePermission(principal, PlatformOperatorPermission.MEMBER_READ_MINIMAL);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("invalid page");
        var result = cases.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "submittedAt").and(Sort.by(Sort.Direction.DESC, "id"))));
        return new CasePageResponse(result.stream().map(this::response).toList(),
                result.getTotalElements(), page, size);
    }

    private CaseResponse response(com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase value) {
        return new CaseResponse(value.getPublicId(), value.getCaseType(), value.getStatus(),
                value.getAccountType(), value.getAccountId(), value.getTargetSupportVersion(),
                value.getRowVersion(), value.getSubmittedAt(), value.getDecisionCode());
    }
}
