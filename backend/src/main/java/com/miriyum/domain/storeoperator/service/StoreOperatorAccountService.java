package com.miriyum.domain.storeoperator.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorAccountUpdateRequest;
import com.miriyum.domain.storeoperator.dto.response.StoreOperatorAccountResponse;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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

    /** 멱등 결과에 기록할 리소스 유형이다(최대 40자). */
    private static final String RESOURCE_TYPE = "STORE_OPERATOR_ACCOUNT";
    private static final String SUCCESS_RESPONSE_CODE = "SUCCESS";

    private final StoreOperatorAccountRepository storeOperatorAccountRepository;
    private final IdempotencyExecutor idempotencyExecutor;

    @Transactional(readOnly = true)
    public StoreOperatorAccountResponse getMe(Long accountId) {
        return StoreOperatorAccountResponse.from(getActiveAccount(accountId));
    }

    /**
     * 표시 이름 수정을 C-006 멱등 계약(#32)에 따라 실행한다.
     *
     * <p>이 메서드가 트랜잭션을 소유하고 {@link IdempotencyExecutor}는 {@code MANDATORY}로 참여한다.
     * 같은 키·같은 지문 재요청은 업무 로직을 다시 실행하지 않고 최초 결과를 재생하며, 같은 키를 다른
     * 지문으로 재사용하면 {@code COMMON_007}로 거절한다.</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public IdempotentOutcome updateDisplayName(
            IdempotencyCommand command,
            Long accountId,
            StoreOperatorAccountUpdateRequest request
    ) {
        return idempotencyExecutor.execute(command, () -> {
            StoreOperatorAccount account = getActiveAccount(accountId);
            account.changeDisplayName(request.displayName());
            return new BusinessResult<>(HttpStatus.OK.value(), SUCCESS_RESPONSE_CODE,
                    RESOURCE_TYPE, String.valueOf(account.getId()),
                    StoreOperatorAccountResponse.from(account));
        });
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
