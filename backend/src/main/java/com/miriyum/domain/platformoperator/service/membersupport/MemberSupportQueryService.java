package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountSnapshot;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSearchCriteria;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.MemberPageResponse;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.MemberResponse;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSupportQueryService {
    private static final Comparator<MemberAccountSnapshot> ORDER = Comparator
            .comparing(MemberAccountSnapshot::joinedAt).reversed()
            .thenComparing(MemberAccountSnapshot::accountId).reversed();
    private final MemberSupportAuthorizationService authorization;
    private final MemberAccountSupportRegistry accounts;

    public MemberSupportQueryService(MemberSupportAuthorizationService authorization,
                                     MemberAccountSupportRegistry accounts) {
        this.authorization = authorization;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public MemberResponse get(PlatformOperatorPrincipal principal, MemberAccountType type, long accountId) {
        authorization.requirePermission(principal, PlatformOperatorPermission.MEMBER_READ_MINIMAL);
        return accounts.require(type).findMinimal(accountId)
                .map(this::response)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public MemberPageResponse list(PlatformOperatorPrincipal principal, MemberAccountType type,
                                   MemberStatus status, MemberSearchCriteria criteria, int page, int size) {
        authorization.requirePermission(principal, PlatformOperatorPermission.MEMBER_READ_MINIMAL);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("invalid page");
        int offset = Math.multiplyExact(page, size);
        int fetch = Math.addExact(offset, size);
        List<MemberAccountSnapshot> merged = new ArrayList<>();
        long total;
        if (type != null) {
            var result = accounts.require(type).search(criteria, 0, fetch);
            merged.addAll(result.content());
            total = result.totalElements();
        } else {
            var consumer = accounts.require(MemberAccountType.CONSUMER).search(criteria, 0, fetch);
            var store = accounts.require(MemberAccountType.STORE_OPERATOR).search(criteria, 0, fetch);
            merged.addAll(consumer.content());
            merged.addAll(store.content());
            total = consumer.totalElements() + store.totalElements();
        }
        List<MemberResponse> content = merged.stream().sorted(ORDER)
                .filter(snapshot -> status == null || status(snapshot) == status)
                .skip(offset).limit(size).map(this::response).toList();
        return new MemberPageResponse(content, total, page, size);
    }

    private MemberResponse response(MemberAccountSnapshot snapshot) {
        return new MemberResponse(snapshot.accountType(), snapshot.accountId(), status(snapshot),
                snapshot.joinedAt(), snapshot.supportVersion());
    }

    private MemberStatus status(MemberAccountSnapshot snapshot) {
        if (snapshot.suspended()) return MemberStatus.TEMPORARILY_SUSPENDED;
        if (snapshot.passwordResetRequired()) return MemberStatus.PASSWORD_RESET_REQUIRED;
        return MemberStatus.ACTIVE;
    }
}
