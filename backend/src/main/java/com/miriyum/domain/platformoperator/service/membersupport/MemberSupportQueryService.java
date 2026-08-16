package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountSnapshot;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.MemberSearchCriteria;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.ActiveSanctionResponse;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.MemberPageResponse;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.MemberResponse;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.PageMetadata;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSupportQueryService {
    private final MemberSupportAuthorizationService authorization;
    private final MemberAccountSupportRegistry accounts;
    private final MemberSanctionRepository sanctions;
    private final Clock clock;
    private final MemberProjectionReader projections;

    public MemberSupportQueryService(MemberSupportAuthorizationService authorization,
                                     MemberAccountSupportRegistry accounts,
                                     MemberSanctionRepository sanctions, Clock clock,
                                     MemberProjectionReader projections) {
        this.authorization = authorization;
        this.accounts = accounts;
        this.sanctions = sanctions;
        this.clock = clock;
        this.projections = projections;
    }

    @Transactional(readOnly = true)
    public MemberResponse get(PlatformOperatorPrincipal principal, MemberAccountType type, long accountId) {
        authorization.requirePermission(principal, PlatformOperatorPermission.MEMBER_READ_MINIMAL);
        return accounts.require(type).findMinimal(accountId)
                .map(this::enrich)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public MemberPageResponse list(PlatformOperatorPrincipal principal, MemberAccountType type,
                                   MemberStatus status, MemberSearchCriteria criteria, int page, int size) {
        authorization.requirePermission(principal, PlatformOperatorPermission.MEMBER_READ_MINIMAL);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("invalid page");
        int offset = Math.multiplyExact(page, size);
        LocalDateTime now = LocalDateTime.now(clock);
        var result = projections.read(type, status, criteria, offset, size, now);
        List<MemberResponse> content = result.content().stream()
                .map(row -> response(row.accountType(), row.accountId(), row.status(),
                        row.joinedAt(), row.supportVersion(),
                        sanctions.findActive(row.accountType(), row.accountId(), now)))
                .toList();
        int totalPages = result.totalElements() == 0 ? 0
                : (int) ((result.totalElements() + size - 1) / size);
        return new MemberPageResponse(content, new PageMetadata(
                page, size, result.totalElements(), totalPages, page + 1 < totalPages));
    }

    private MemberResponse enrich(MemberAccountSnapshot snapshot) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<MemberSanction> active = sanctions.findActive(snapshot.accountType(), snapshot.accountId(), now);
        return response(snapshot.accountType(), snapshot.accountId(), status(snapshot, active),
                snapshot.joinedAt(), snapshot.supportVersion(), active);
    }

    private MemberResponse response(MemberAccountType accountType, long accountId, MemberStatus status,
                                    java.time.Instant joinedAt, long supportVersion,
                                    List<MemberSanction> active) {
        List<ActiveSanctionResponse> summaries = active.stream()
                .map(sanction -> new ActiveSanctionResponse(
                        sanction.getLevel(), sanction.restrictedFeatures(),
                        sanction.getEndsAt() == null ? null : sanction.getEndsAt().atOffset(ZoneOffset.UTC)))
                .toList();
        return new MemberResponse(accountType, Long.toString(accountId), status, joinedAt, supportVersion, summaries);
    }

    private MemberStatus status(MemberAccountSnapshot snapshot, List<MemberSanction> active) {
        if (active.stream().anyMatch(s -> s.getLevel() == MemberSanctionLevel.PERMANENT_SUSPENSION))
            return MemberStatus.PERMANENTLY_SUSPENDED;
        if (active.stream().anyMatch(s -> s.getLevel() == MemberSanctionLevel.TEMPORARY_SUSPENSION)
                || snapshot.suspended()) return MemberStatus.TEMPORARILY_SUSPENDED;
        if (snapshot.passwordResetRequired()) return MemberStatus.PASSWORD_RESET_REQUIRED;
        if (active.stream().anyMatch(s -> s.getLevel() == MemberSanctionLevel.FEATURE_RESTRICTION))
            return MemberStatus.FEATURE_RESTRICTED;
        return MemberStatus.ACTIVE;
    }
}
