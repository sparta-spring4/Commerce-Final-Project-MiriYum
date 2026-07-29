package com.miriyum.domain.consumer.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.consumer.dto.request.ConsumerAccountUpdateRequest;
import com.miriyum.domain.consumer.dto.response.ConsumerAccountResponse;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 사용자 본인 정보 조회·수정(마이페이지)을 담당한다. Access JWT의 principal은 신뢰하되,
 * 계정 상태는 매 요청 DB에서 다시 확인해 토큰 발급 뒤 정지된 계정을 걸러낸다.
 */
@Service
@RequiredArgsConstructor
public class ConsumerAccountService {

    private final ConsumerAccountRepository consumerAccountRepository;

    @Transactional(readOnly = true)
    public ConsumerAccountResponse getMe(Long accountId) {
        return ConsumerAccountResponse.from(getActiveAccount(accountId));
    }

    @Transactional
    public ConsumerAccountResponse updateName(Long accountId, ConsumerAccountUpdateRequest request) {
        ConsumerAccount account = getActiveAccount(accountId);
        account.changeName(request.name());
        return ConsumerAccountResponse.from(account);
    }

    private ConsumerAccount getActiveAccount(Long accountId) {
        ConsumerAccount account = consumerAccountRepository.findById(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED));
        if (account.getStatus() != ConsumerAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        return account;
    }
}
