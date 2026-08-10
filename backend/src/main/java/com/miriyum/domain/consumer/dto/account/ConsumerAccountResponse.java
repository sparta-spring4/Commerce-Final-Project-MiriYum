package com.miriyum.domain.consumer.dto.account;

import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.auth.contact.PhoneNumberMasker;

/**
 * 일반 사용자 계정 응답이다. 본인 정보 조회·수정 응답에서 쓴다.
 */
public record ConsumerAccountResponse(
        String accountId,
        String email,
        String phoneNumber,
        String nickname,
        String status
) {

    public static ConsumerAccountResponse from(ConsumerAccount account) {
        return new ConsumerAccountResponse(
                String.valueOf(account.getId()),
                account.getEmail(),
                PhoneNumberMasker.mask(account.getPhone()),
                account.getName(),
                account.getStatus().name()
        );
    }
}
