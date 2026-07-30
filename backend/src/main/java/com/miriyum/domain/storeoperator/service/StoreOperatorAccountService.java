package com.miriyum.domain.storeoperator.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorAccountUpdateRequest;
import com.miriyum.domain.storeoperator.dto.response.StoreOperatorAccountResponse;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 운영자 본인 정보 조회·수정(마이페이지)을 담당한다. Access JWT의 principal은 신뢰하되,
 * 계정 상태는 매 요청 DB에서 다시 확인해 토큰 발급 뒤 정지된 계정을 걸러낸다.
 *
 * <p>표시 이름은 일반 사용자 닉네임과 달리 변경 주기 제한이나 허용 문자·예약어 검사가 정책상
 * 없다({@code docs/specs/auth-account/spec.md} "본인 정보" 절).</p>
 */
@Service
@RequiredArgsConstructor
public class StoreOperatorAccountService {

    private final StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Transactional(readOnly = true)
    public StoreOperatorAccountResponse getMe(Long accountId) {
        return StoreOperatorAccountResponse.from(getActiveAccount(accountId));
    }

    @Transactional
    public StoreOperatorAccountResponse updateDisplayName(Long accountId, StoreOperatorAccountUpdateRequest request) {
        StoreOperatorAccount account = getActiveAccount(accountId);
        account.changeDisplayName(request.displayName());
        return StoreOperatorAccountResponse.from(account);
    }

    private StoreOperatorAccount getActiveAccount(Long accountId) {
        StoreOperatorAccount account = storeOperatorAccountRepository.findById(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED));
        if (account.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        return account;
    }
}
