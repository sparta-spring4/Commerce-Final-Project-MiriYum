package com.miriyum.domain.storeoperator.dto.account;

import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.auth.contact.PhoneNumberMasker;

/**
 * 매장 운영자 계정 응답이다. 본인 정보 조회·수정 응답에서 쓴다.
 */
public record StoreOperatorAccountResponse(
        String accountId,
        String email,
        String phoneNumber,
        String displayName,
        String status
) {

    public static StoreOperatorAccountResponse from(StoreOperatorAccount account) {
        return new StoreOperatorAccountResponse(
                String.valueOf(account.getId()),
                account.getEmail(),
                PhoneNumberMasker.mask(account.getPhone()),
                account.getDisplayName(),
                account.getStatus().name()
        );
    }
}
