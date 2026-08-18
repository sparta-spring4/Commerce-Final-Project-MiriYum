package com.miriyum.domain.platformoperator.dto.management;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Locale;
import java.util.Set;

/** 운영자 계정 목록의 허용된 필터·페이지·정렬만 보존하는 정규화 요청이다. */
public record PlatformOperatorAccountSearchRequest(
        PlatformOperatorAccountStatus status,
        PlatformOperatorRole role,
        String query,
        int page,
        int size,
        String sortField,
        String sortDirection
) {
    private static final Set<String> SORT_FIELDS = Set.of("operatorId", "displayName", "status", "lastLoginAt");

    public static PlatformOperatorAccountSearchRequest of(
            String status, String role, String query, Integer page, Integer size, String sort) {
        try {
            int normalizedPage = page == null ? 0 : page;
            int normalizedSize = size == null ? 20 : size;
            String normalizedQuery = query == null ? null : query.strip();
            if (normalizedPage < 0 || normalizedSize < 1 || normalizedSize > 100
                    || normalizedQuery != null && (normalizedQuery.isEmpty() || normalizedQuery.length() > 100)) {
                throw invalid();
            }
            String[] order = sort == null ? new String[] {"operatorId", "asc"} : sort.split(",", -1);
            if (order.length != 2 || !SORT_FIELDS.contains(order[0])
                    || !(order[1].equalsIgnoreCase("asc") || order[1].equalsIgnoreCase("desc"))) {
                throw invalid();
            }
            return new PlatformOperatorAccountSearchRequest(
                    status == null ? null : PlatformOperatorAccountStatus.valueOf(status.toUpperCase(Locale.ROOT)),
                    role == null ? null : PlatformOperatorRole.valueOf(role.toUpperCase(Locale.ROOT)),
                    normalizedQuery,
                    normalizedPage,
                    normalizedSize,
                    order[0],
                    order[1].toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static ServiceException invalid() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
