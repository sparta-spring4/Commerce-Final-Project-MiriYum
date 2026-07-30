package com.miriyum.domain.storeoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorAccountUpdateRequest;
import com.miriyum.domain.storeoperator.dto.response.StoreOperatorAccountResponse;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreOperatorAccountServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    private static final IdempotencyCommand UPDATE_COMMAND = new IdempotencyCommand(
            "store-operator", ACCOUNT_ID, "STORE_OPERATOR_ACCOUNT_UPDATE",
            "123e4567-e89b-12d3-a456-426614174000", "a".repeat(64));

    @Mock
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    private StoreOperatorAccountService storeOperatorAccountService;

    @BeforeEach
    void setUp() {
        storeOperatorAccountService =
                new StoreOperatorAccountService(storeOperatorAccountRepository, idempotencyExecutor);
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
    @DisplayName("존재하지 않는 계정을 조회하면 AUTH_011을 던진다")
    void rejectsUnknownAccount() {
        // given
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> storeOperatorAccountService.getMe(ACCOUNT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED);
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
