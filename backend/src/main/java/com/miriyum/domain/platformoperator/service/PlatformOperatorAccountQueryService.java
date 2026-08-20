package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountDetailData;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountPageData;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountSearchRequest;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountSummaryData;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPasswordState;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.PageMetadata;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기존 플랫폼 운영자 원장에서 본인·계정·권한 읽기 projection을 조합한다. */
@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorAccountQueryService {
    private final PlatformOperatorAccountRepository accounts;
    private final PlatformOperatorRoleGrantRepository roles;
    private final PlatformOperatorPermissionGrantRepository permissions;
    private final PlatformOperatorAuthEventRepository authEvents;
    private final OperatorAuthorityReader authorities;

    public PlatformOperatorAccountQueryService(
            PlatformOperatorAccountRepository accounts,
            PlatformOperatorRoleGrantRepository roles,
            PlatformOperatorPermissionGrantRepository permissions,
            PlatformOperatorAuthEventRepository authEvents,
            OperatorAuthorityReader authorities) {
        this.accounts = accounts;
        this.roles = roles;
        this.permissions = permissions;
        this.authEvents = authEvents;
        this.authorities = authorities;
    }

    @Transactional(readOnly = true)
    public PlatformOperatorAccountPageData search(
            PlatformOperatorPrincipal principal, PlatformOperatorAccountSearchRequest request) {
        requireManagePermission(principal);
        Long queryId = numericQuery(request.query());
        var page = accounts.searchAccounts(
                request.status() == null ? null : request.status().name(),
                request.role() == null ? null : request.role().name(),
                request.query(), queryId, request.sortField(), request.sortDirection(),
                PageRequest.of(request.page(), request.size()));
        List<Long> ids = page.getContent().stream().map(PlatformOperatorAccountRepository.AccountQueryRow::getOperatorId)
                .toList();
        Map<Long, List<PlatformOperatorRole>> roleMap = rolesByAccount(ids);
        List<PlatformOperatorAccountSummaryData> content = page.getContent().stream().map(row ->
                new PlatformOperatorAccountSummaryData(
                        String.valueOf(row.getOperatorId()), PlatformOperatorEmailMasker.mask(row.getEmail()),
                        row.getDisplayName(), row.getStatus(),
                        PlatformOperatorPasswordState.TEMPORARY.name().equals(row.getPasswordState()),
                        row.getAuthorityVersion(), roleMap.getOrDefault(row.getOperatorId(), List.of()),
                        row.getLastLoginAt() == null ? null : row.getLastLoginAt().toInstant(ZoneOffset.UTC))).toList();
        return new PlatformOperatorAccountPageData(content, new PageMetadata(
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext()));
    }

    @Transactional(readOnly = true)
    public PlatformOperatorAccountDetailData detail(PlatformOperatorPrincipal principal, long operatorId) {
        requireManagePermission(principal);
        PlatformOperatorAccount account = accounts.findById(operatorId)
                .orElseThrow(() -> new ServiceException(AdminAuthorizationErrorCode.OPERATOR_ACCOUNT_NOT_FOUND));
        Set<PlatformOperatorRole> assignedRoles = EnumSet.noneOf(PlatformOperatorRole.class);
        roles.findAllByPlatformOperatorAccountId(operatorId).forEach(grant -> assignedRoles.add(grant.getRole()));
        Set<PlatformOperatorPermission> direct = EnumSet.noneOf(PlatformOperatorPermission.class);
        permissions.findAllByPlatformOperatorAccountId(operatorId)
                .forEach(grant -> direct.add(grant.getPermission()));
        Set<PlatformOperatorPermission> effective = EnumSet.noneOf(PlatformOperatorPermission.class);
        effective.addAll(direct);
        assignedRoles.forEach(role -> effective.addAll(role.permissions()));
        return new PlatformOperatorAccountDetailData(
                String.valueOf(account.getId()), PlatformOperatorEmailMasker.mask(account.getEmail()),
                account.getDisplayName(), account.getStatus().name(),
                account.getPasswordState() == PlatformOperatorPasswordState.TEMPORARY,
                account.getAuthorityVersion(), sorted(assignedRoles), sorted(direct), sorted(effective),
                authEvents.findLatestOccurredAt(operatorId, PlatformOperatorAuthEventType.LOGIN,
                        PlatformOperatorAuthEventOutcome.SUCCESS).orElse(null));
    }

    private OperatorAuthority currentAuthority(PlatformOperatorPrincipal principal) {
        return authorities.requireCurrentAuthority(principal.accountId(), principal.authorityVersion());
    }

    private void requireManagePermission(PlatformOperatorPrincipal principal) {
        if (!currentAuthority(principal).permissions().contains(PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }

    private Map<Long, List<PlatformOperatorRole>> rolesByAccount(Collection<Long> accountIds) {
        if (accountIds.isEmpty()) return Map.of();
        Map<Long, List<PlatformOperatorRole>> result = new HashMap<>();
        roles.findAllByPlatformOperatorAccountIdIn(accountIds).forEach(grant ->
                result.computeIfAbsent(grant.getPlatformOperatorAccountId(), ignored -> new ArrayList<>())
                        .add(grant.getRole()));
        result.replaceAll((ignored, values) -> values.stream().sorted(Comparator.comparing(Enum::name)).toList());
        return result;
    }

    private static Long numericQuery(String query) {
        if (query == null || !query.matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.valueOf(query);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static <E extends Enum<E>> List<E> sorted(Collection<E> values) {
        return values.stream().sorted(Comparator.comparing(Enum::name)).toList();
    }
}
