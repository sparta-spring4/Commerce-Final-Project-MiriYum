package com.miriyum.domain.storeoperator.service;

import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorContactRegistrationRequest;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorAccountUpdateRequest;
import com.miriyum.domain.storeoperator.dto.response.StoreOperatorAccountResponse;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final PhoneNumberPolicy phoneNumberPolicy;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public StoreOperatorAccountResponse getMe(Long accountId) {
        return StoreOperatorAccountResponse.from(getActiveAccount(accountId));
    }

    /**
     * Controller가 업무 입력을 처리하기 전에 C-013 계정 상태 경계를 확인한다.
     */
    @Transactional(readOnly = true)
    public void requireActiveAccount(Long accountId) {
        getActiveAccount(accountId);
    }

    /**
     * 기존 매장 운영자 계정의 최초 연락처를 등록한다. 운영자 연락처는 예약 참조를 만들지 않는다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public IdempotentOutcome registerContact(
            IdempotencyCommand command,
            Long accountId,
            StoreOperatorContactRegistrationRequest request
    ) {
        getActiveAccount(accountId);
        return idempotencyExecutor.execute(command, () -> {
            StoreOperatorAccount account = getActiveAccountForUpdate(accountId);
            String normalizedPhone = phoneNumberPolicy.normalize(request.phoneNumber());
            if (account.getPhone() != null) {
                String existingNormalizedPhone = phoneNumberPolicy.normalize(account.getPhone());
                if (!existingNormalizedPhone.equals(normalizedPhone)) {
                    throw new ServiceException(AccountErrorCode.CONTACT_CHANGE_NOT_ALLOWED);
                }
                if (!account.getPhone().equals(normalizedPhone)) {
                    account.registerContact(normalizedPhone);
                }
            }
            if (account.getPhone() == null && storeOperatorAccountRepository.existsByPhone(normalizedPhone)) {
                throw new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
            }
            try {
                if (account.getPhone() == null) {
                    account.registerContact(normalizedPhone);
                }
                storeOperatorAccountRepository.saveAndFlush(account);
            } catch (DataIntegrityViolationException exception) {
                throw mapDuplicateConstraint(exception);
            }
            return new BusinessResult<>(HttpStatus.OK.value(), SUCCESS_RESPONSE_CODE,
                    RESOURCE_TYPE, String.valueOf(account.getId()),
                    StoreOperatorAccountResponse.from(account));
        });
    }

    /**
     * 표시 이름 수정을 C-006 멱등 계약(#32)에 따라 실행한다.
     *
     * <p>이 메서드가 트랜잭션을 소유하고 {@link IdempotencyExecutor}는 {@code MANDATORY}로 참여한다.
     * 같은 키·같은 지문 재요청은 업무 로직을 다시 실행하지 않고 최초 결과를 재생하며, 같은 키를 다른
     * 지문으로 재사용하면 {@code COMMON_007}로 거절한다.</p>
     *
     * <p>현재 계정의 존재·활성 확인은 요청마다 성립해야 하는 인증 경계이므로 멱등 실행기 <b>앞</b>에서
     * 수행한다. 콜백 안에 두면 재생 경로에서 콜백이 실행되지 않아, 최초 수정 이후 계정이 사라져도
     * 같은 키·같은 지문 재요청이 저장된 200을 그대로 돌려준다.</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public IdempotentOutcome updateDisplayName(
            IdempotencyCommand command,
            Long accountId,
            StoreOperatorAccountUpdateRequest request
    ) {
        StoreOperatorAccount account = getActiveAccount(accountId);
        return idempotencyExecutor.execute(command, () -> {
            account.changeDisplayName(request.displayName());
            return new BusinessResult<>(HttpStatus.OK.value(), SUCCESS_RESPONSE_CODE,
                    RESOURCE_TYPE, String.valueOf(account.getId()),
                    StoreOperatorAccountResponse.from(account));
        });
    }

    /**
     * JWT subject에 해당하는 현재 계정을 확인한다.
     *
     * <p>{@code docs/specs/mvp1-common/spec.md}의 {@code C-013}은 "subject에 해당하는 현재 계정을
     * 확인할 수 없음"을 {@code 401}로, "유효한 principal이지만 현재 계정 상태가 이용을 허용하지 않음"을
     * {@code 403}으로 구분한다. 계정이 없으면 그 토큰으로는 더 이상 주체를 특정할 수 없으므로 일반
     * Access Token 오류와 같은 {@code AUTH_003}(401)으로 응답한다. 오류 코드와 메시지가 같아
     * 응답만으로는 계정 삭제 여부를 알 수 없다(이슈 #72).</p>
     */
    private StoreOperatorAccount getActiveAccount(Long accountId) {
        StoreOperatorAccount account = storeOperatorAccountRepository.findById(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID));
        if (account.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        return account;
    }

    private StoreOperatorAccount getActiveAccountForUpdate(Long accountId) {
        entityManager.clear();
        StoreOperatorAccount account = storeOperatorAccountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID));
        if (account.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        return account;
    }

    private ServiceException mapDuplicateConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("uk_store_operator_accounts_phone")) {
            return new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
        }
        return new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }
}
