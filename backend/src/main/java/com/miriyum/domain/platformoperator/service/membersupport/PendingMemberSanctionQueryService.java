package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.PendingSanctionApprovalPageResponse;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.PendingSanctionApprovalResponse;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.response.PageMetadata;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class PendingMemberSanctionQueryService {
    private final OperatorAuthorityReader authorities;
    private final MemberSanctionRepository sanctions;

    public PendingMemberSanctionQueryService(OperatorAuthorityReader authorities,
                                             MemberSanctionRepository sanctions) {
        this.authorities = authorities;
        this.sanctions = sanctions;
    }

    @Transactional(readOnly = true)
    public PendingSanctionApprovalPageResponse list(
            PlatformOperatorPrincipal principal, int page, int size) {
        var authority = authorities.requireCurrentAuthority(
                principal.accountId(), principal.authorityVersion());
        if (!authority.permissions().contains(
                PlatformOperatorPermission.ACCOUNT_PERMANENT_SANCTION_APPROVE)
                || !authority.roles().contains(PlatformOperatorRole.SUPER_ADMIN)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        var pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "proposedAt")
                        .and(Sort.by(Sort.Direction.DESC, "id")));
        var result = sanctions.findApprovalCandidates(
                MemberSanctionStatus.PENDING_ADDITIONAL_APPROVAL,
                principal.accountId(), pageable);
        return new PendingSanctionApprovalPageResponse(
                result.stream().map(this::response).toList(),
                new PageMetadata(result.getNumber(), result.getSize(), result.getTotalElements(),
                        result.getTotalPages(), result.hasNext()));
    }

    private PendingSanctionApprovalResponse response(MemberSanction sanction) {
        return new PendingSanctionApprovalResponse(
                sanction.getPublicId(),
                sanction.getSupportCase().getRowVersion(),
                sanction.getAccountType(),
                Long.toString(sanction.getAccountId()),
                sanction.getReasonCode(),
                sanction.getPolicyVersion(),
                sanction.getProposedAt().atOffset(ZoneOffset.UTC));
    }
}
