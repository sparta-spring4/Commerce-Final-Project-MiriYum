package com.miriyum.domain.consumer.service;

import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.consumer.dto.request.ConsumerAccountUpdateRequest;
import com.miriyum.domain.consumer.dto.response.ConsumerAccountResponse;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 사용자 본인 정보 조회·수정(마이페이지)을 담당한다. Access JWT의 principal은 신뢰하되,
 * 계정 상태는 매 요청 DB에서 다시 확인해 토큰 발급 뒤 정지된 계정을 걸러낸다.
 */
@Service
@RequiredArgsConstructor
public class ConsumerAccountService {

    private static final long NICKNAME_CHANGE_COOLDOWN_DAYS = 7;

    /** 멱등 결과에 기록할 리소스 유형이다(최대 40자). */
    private static final String RESOURCE_TYPE = "CONSUMER_ACCOUNT";
    private static final String SUCCESS_RESPONSE_CODE = "SUCCESS";

    private final ConsumerAccountRepository consumerAccountRepository;
    private final NicknamePolicy nicknamePolicy;
    private final Clock clock;
    private final IdempotencyExecutor idempotencyExecutor;

    @Transactional(readOnly = true)
    public ConsumerAccountResponse getMe(Long accountId) {
        return ConsumerAccountResponse.from(getActiveAccount(accountId));
    }

    /**
     * 닉네임 수정을 C-006 멱등 계약(#32)에 따라 실행한다.
     *
     * <p>이 메서드가 트랜잭션을 소유하고 {@link IdempotencyExecutor}는 {@code MANDATORY}로 참여한다.
     * 같은 키·같은 지문 재요청은 업무 로직을 다시 실행하지 않으므로 7일 변경 제한도 다시 걸리지 않고
     * 최초 결과를 재생한다. 같은 키를 다른 지문으로 재사용하면 {@code COMMON_007}로 거절한다.</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public IdempotentOutcome updateName(
            IdempotencyCommand command,
            Long accountId,
            ConsumerAccountUpdateRequest request
    ) {
        return idempotencyExecutor.execute(command, () -> {
            ConsumerAccount account = getActiveAccount(accountId);
            LocalDateTime now = LocalDateTime.now(clock);
            requireNicknameChangeAllowed(account, now);
            String normalizedNickname = nicknamePolicy.normalize(request.nickname());
            account.changeName(normalizedNickname, now);
            return new BusinessResult<>(HttpStatus.OK.value(), SUCCESS_RESPONSE_CODE,
                    RESOURCE_TYPE, String.valueOf(account.getId()),
                    ConsumerAccountResponse.from(account));
        });
    }

    private void requireNicknameChangeAllowed(ConsumerAccount account, LocalDateTime now) {
        LocalDateTime lastChangedAt = account.getNicknameChangedAt();
        if (lastChangedAt != null && lastChangedAt.plusDays(NICKNAME_CHANGE_COOLDOWN_DAYS).isAfter(now)) {
            throw new ServiceException(AccountErrorCode.NICKNAME_CHANGE_TOO_SOON);
        }
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
