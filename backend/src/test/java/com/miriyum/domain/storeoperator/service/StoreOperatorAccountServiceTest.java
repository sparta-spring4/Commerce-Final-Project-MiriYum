package com.miriyum.domain.storeoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.storeoperator.dto.account.StoreOperatorContactRegistrationRequest;
import com.miriyum.domain.storeoperator.dto.account.StoreOperatorAccountUpdateRequest;
import com.miriyum.domain.storeoperator.dto.account.StoreOperatorAccountResponse;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreOperatorAccountServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    private static final IdempotencyCommand UPDATE_COMMAND = new IdempotencyCommand(
            "store-operator", ACCOUNT_ID, "STORE_OPERATOR_ACCOUNT_UPDATE",
            "123e4567-e89b-12d3-a456-426614174000", "a".repeat(64));

    private static final IdempotencyCommand CONTACT_COMMAND = new IdempotencyCommand(
            "store-operator", ACCOUNT_ID, "STORE_OPERATOR_CONTACT_REGISTER",
            "123e4567-e89b-12d3-a456-426614174001", "b".repeat(64));

    @Mock
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    @Mock
    private EntityManager entityManager;

    private StoreOperatorAccountService storeOperatorAccountService;

    @BeforeEach
    void setUp() {
        storeOperatorAccountService =
                new StoreOperatorAccountService(
                        storeOperatorAccountRepository, idempotencyExecutor, new PhoneNumberPolicy());
        ReflectionTestUtils.setField(storeOperatorAccountService, "entityManager", entityManager);
    }

    @Test
    @DisplayName("활성 계정의 본인 정보를 조회한다")
    void getsActiveAccount() {
        // given
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when
        StoreOperatorAccountResponse response = storeOperatorAccountService.getMe(ACCOUNT_ID);

        // then
        assertThat(response.displayName()).isEqualTo("미리윰식당");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("존재하지 않는 계정을 조회하면 C-013에 따라 401 AUTH_003을 던진다")
    void rejectsUnknownAccount() {
        // given
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> storeOperatorAccountService.getMe(ACCOUNT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    @DisplayName("비활성 계정을 조회하면 계정 부재와 달리 403 AUTH_011을 던진다")
    void rejectsSuspendedAccount() {
        // given
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        ReflectionTestUtils.setField(account, "status", StoreOperatorAccountStatus.SUSPENDED);
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when & then
        assertThatThrownBy(() -> storeOperatorAccountService.getMe(ACCOUNT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED);
    }

    @Test
    @DisplayName("계정 부재와 비활성 계정은 서로 다른 상태 코드로 갈린다")
    void separatesMissingAccountFromSuspendedAccount() {
        assertThat(AuthErrorCode.ACCESS_TOKEN_INVALID.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(AuthErrorCode.ACCOUNT_RESTRICTED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("전화번호가 없는 기존 운영자는 최초 연락처를 등록할 수 있다")
    void registersFirstContact() {
        // given
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(storeOperatorAccountRepository.findByIdForUpdate(ACCOUNT_ID)).willReturn(Optional.of(account));
        AtomicReference<BusinessResult<?>> businessResult = runBusinessWorkOnExecute();

        // when
        storeOperatorAccountService.registerContact(
                CONTACT_COMMAND, ACCOUNT_ID, new StoreOperatorContactRegistrationRequest("010-1234-5678"));

        // then
        assertThat(account.getPhone()).isEqualTo("01012345678");
        assertThat(businessResult.get().data())
                .isInstanceOfSatisfying(StoreOperatorAccountResponse.class,
                        response -> assertThat(response.phoneNumber()).isEqualTo("010-****-5678"));
    }

    @Test
    @DisplayName("기존 운영자 연락처의 공백·하이픈 형식이 달라도 같은 번호로 등록을 완료한다")
    void normalizesExistingContactBeforeComparison() {
        // given
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        account.registerContact("010-1234-5678");
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(storeOperatorAccountRepository.findByIdForUpdate(ACCOUNT_ID)).willReturn(Optional.of(account));
        AtomicReference<BusinessResult<?>> businessResult = runBusinessWorkOnExecute();

        // when
        storeOperatorAccountService.registerContact(
                CONTACT_COMMAND, ACCOUNT_ID, new StoreOperatorContactRegistrationRequest("01012345678"));

        // then
        assertThat(account.getPhone()).isEqualTo("01012345678");
        assertThat(businessResult.get().data()).isInstanceOf(StoreOperatorAccountResponse.class);
    }

    @Test
    @DisplayName("최초 등록 뒤 운영자 연락처를 바꾸면 ACCOUNT_007을 던진다")
    void rejectsChangingRegisteredContact() {
        // given
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        account.registerContact("01012345678");
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(storeOperatorAccountRepository.findByIdForUpdate(ACCOUNT_ID)).willReturn(Optional.of(account));
        runBusinessWorkOnExecute();

        // when & then
        assertThatThrownBy(() -> storeOperatorAccountService.registerContact(
                CONTACT_COMMAND, ACCOUNT_ID, new StoreOperatorContactRegistrationRequest("010-9999-9999")))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AccountErrorCode.CONTACT_CHANGE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("계정이 없으면 멱등 실행기를 호출하기 전에 401 AUTH_003으로 거절한다")
    void rejectsUpdateBeforeIdempotentExecutionWhenAccountMissing() {
        // given
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> storeOperatorAccountService.updateDisplayName(
                UPDATE_COMMAND, ACCOUNT_ID, new StoreOperatorAccountUpdateRequest("새상호명")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_TOKEN_INVALID);

        // 재생 경로는 업무 콜백을 실행하지 않으므로, 계정 확인이 콜백 안에 있으면 저장된 200이
        // 그대로 나간다. 실행기에 들어가기 전에 막혔는지를 호출 자체로 고정한다.
        then(idempotencyExecutor).should(never()).execute(any(), any());
    }

    @Test
    @DisplayName("표시 이름을 수정하면 멱등 실행기에 새 값을 담은 업무 결과를 넘긴다")
    void updatesDisplayName() {
        // given
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "이전상호명");
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        AtomicReference<BusinessResult<?>> businessResult = runBusinessWorkOnExecute();

        // when
        storeOperatorAccountService.updateDisplayName(
                UPDATE_COMMAND, ACCOUNT_ID, new StoreOperatorAccountUpdateRequest("새상호명"));

        // then
        assertThat(account.getDisplayName()).isEqualTo("새상호명");
        assertThat(businessResult.get().httpStatus()).isEqualTo(200);
        assertThat(businessResult.get().resourceType()).isEqualTo("STORE_OPERATOR_ACCOUNT");
        assertThat(businessResult.get().data())
                .isInstanceOfSatisfying(StoreOperatorAccountResponse.class,
                        response -> assertThat(response.displayName()).isEqualTo("새상호명"));
    }

    /**
     * 실제 {@code IdempotencyExecutor}는 DB 선점이 필요하므로, 단위 테스트에서는 업무 콜백만
     * 그대로 실행하고 그 결과를 꺼내볼 수 있게 대역을 세운다(신규 선점 경로와 같다).
     */
    private AtomicReference<BusinessResult<?>> runBusinessWorkOnExecute() {
        AtomicReference<BusinessResult<?>> captured = new AtomicReference<>();
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> businessWork = invocation.getArgument(1);
            BusinessResult<?> result = businessWork.get();
            captured.set(result);
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(), null);
        });
        return captured;
    }
}
