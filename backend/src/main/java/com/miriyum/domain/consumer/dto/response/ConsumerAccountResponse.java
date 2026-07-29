package com.miriyum.domain.consumer.dto.response;

import com.miriyum.domain.consumer.entity.ConsumerAccount;

/**
 * 일반 사용자 계정 응답이다. 가입 성공, 본인 정보 조회 응답에서 공통으로 쓴다.
 */
public record ConsumerAccountResponse(
        String accountId,
        String email,
        String phone,
        String name,
        String status
) {

    public static ConsumerAccountResponse from(ConsumerAccount account) {
        return new ConsumerAccountResponse(
                String.valueOf(account.getId()),
                account.getEmail(),
                account.getPhone(),
                account.getName(),
                account.getStatus().name()
        );
    }
}
