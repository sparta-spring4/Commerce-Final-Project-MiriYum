package com.miriyum.domain.consumer.service;

import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.contact.ReservationContactReferenceGenerator;
import com.miriyum.domain.consumer.dto.account.ConsumerAccountUpdateRequest;
import com.miriyum.domain.consumer.dto.account.ConsumerContactRegistrationRequest;
import com.miriyum.domain.consumer.dto.account.ConsumerAccountResponse;
import com.miriyum.domain.consumer.dto.contract.ReservationContactResult;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.LocalDateTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final PhoneNumberPolicy phoneNumberPolicy;
    private final ReservationContactReferenceGenerator contactReferenceGenerator;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public ConsumerAccountResponse getMe(Long accountId) {
        return ConsumerAccountResponse.from(getActiveAccount(accountId));
    }

    /**
     * Controller가 업무 입력을 처리하기 전에 C-013 계정 상태 경계를 확인한다.
     */
    @Transactional(readOnly = true)
    public void requireActiveAccount(Long accountId) {
        getActiveAccount(accountId);
    }

    /**
     * 기존 계정의 최초 연락처를 등록한다. 연락처 원문은 Reservation으로 전달하지 않고,
     * 소비자 계정에 저장한 불투명 참조만 이후 예약 생성 경계에서 제공한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public IdempotentOutcome registerContact(
            IdempotencyCommand command,
            Long accountId,
            ConsumerContactRegistrationRequest request
    ) {
        getActiveAccount(accountId);
        return idempotencyExecutor.execute(command, () -> {
            ConsumerAccount account = getActiveAccountForUpdate(accountId);
            String normalizedPhone = phoneNumberPolicy.normalize(request.phoneNumber());
            if (account.getPhone() == null && consumerAccountRepository.existsByPhone(normalizedPhone)) {
                throw new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
            }
            try {
                registerContactIfAllowed(account, normalizedPhone);
                consumerAccountRepository.saveAndFlush(account);
            } catch (DataIntegrityViolationException exception) {
                throw mapDuplicateConstraint(exception);
            }
            return new BusinessResult<>(HttpStatus.OK.value(), SUCCESS_RESPONSE_CODE,
                    RESOURCE_TYPE, String.valueOf(account.getId()),
                    ConsumerAccountResponse.from(account));
        });
    }

    /**
     * 예약 생성 시 Auth가 제공하는 연락처 결과다.
     */
    @Transactional(readOnly = true)
    public ReservationContactResult getReservationContact(Long accountId) {
        ConsumerAccount account = getActiveAccount(accountId);
        if (account.getPhone() == null) {
            throw new ServiceException(AccountErrorCode.RESERVATION_CONTACT_REQUIRED);
        }
        String reference = account.getReservationContactReference();
        if (reference == null || reference.isBlank()) {
            throw new ServiceException(AccountErrorCode.RESERVATION_CONTACT_REQUIRED);
        }
        return new ReservationContactResult(reference, true);
    }

    /**
     * 닉네임 수정을 C-006 멱등 계약(#32)에 따라 실행한다.
     *
     * <p>이 메서드가 트랜잭션을 소유하고 {@link IdempotencyExecutor}는 {@code MANDATORY}로 참여한다.
     * 같은 키·같은 지문 재요청은 업무 로직을 다시 실행하지 않으므로 7일 변경 제한도 다시 걸리지 않고
     * 최초 결과를 재생한다. 같은 키를 다른 지문으로 재사용하면 {@code COMMON_007}로 거절한다.</p>
     *
     * <p>현재 계정의 존재·활성 확인은 요청마다 성립해야 하는 인증 경계이므로 멱등 실행기 <b>앞</b>에서
     * 수행한다. 콜백 안에 두면 재생 경로에서 콜백이 실행되지 않아, 최초 수정 이후 계정이 사라져도
     * 같은 키·같은 지문 재요청이 저장된 200을 그대로 돌려준다. 반대로 닉네임 변경 주기처럼 재생 시
     * 다시 적용하면 안 되는 가변 업무 검증은 콜백 안에 남긴다.</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public IdempotentOutcome updateName(
            IdempotencyCommand command,
            Long accountId,
            ConsumerAccountUpdateRequest request
    ) {
        ConsumerAccount account = getActiveAccount(accountId);
        return idempotencyExecutor.execute(command, () -> {
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

    private void registerContactIfAllowed(ConsumerAccount account, String normalizedPhone) {
        if (account.getPhone() != null) {
            String existingNormalizedPhone = phoneNumberPolicy.normalize(account.getPhone());
            if (!existingNormalizedPhone.equals(normalizedPhone)) {
                throw new ServiceException(AccountErrorCode.CONTACT_CHANGE_NOT_ALLOWED);
            }
            if (!account.getPhone().equals(normalizedPhone)) {
                account.registerContact(normalizedPhone, account.getReservationContactReference());
            }
        }
        if (account.getPhone() == null || account.getReservationContactReference() == null) {
            account.registerContact(normalizedPhone, contactReferenceGenerator.generate());
        }
    }

    private ServiceException mapDuplicateConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("uk_consumer_accounts_phone")) {
            return new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
        }
        return new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
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
    private ConsumerAccount getActiveAccount(Long accountId) {
        ConsumerAccount account = consumerAccountRepository.findById(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID));
        if (account.getStatus() != ConsumerAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        return account;
    }

    private ConsumerAccount getActiveAccountForUpdate(Long accountId) {
        entityManager.clear();
        ConsumerAccount account = consumerAccountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID));
        if (account.getStatus() != ConsumerAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        return account;
    }
}
