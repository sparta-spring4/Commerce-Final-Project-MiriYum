package com.miriyum.domain.storeoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorAccountUpdateRequest;
import com.miriyum.domain.storeoperator.dto.response.StoreOperatorAccountResponse;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreOperatorAccountServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    private StoreOperatorAccountService storeOperatorAccountService;

    @BeforeEach
    void setUp() {
        storeOperatorAccountService = new StoreOperatorAccountService(storeOperatorAccountRepository);
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
    @DisplayName("표시 이름을 수정하면 새 값을 반환한다")
    void updatesDisplayName() {
        // given
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "이전상호명");
        given(storeOperatorAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when
        StoreOperatorAccountResponse response = storeOperatorAccountService.updateDisplayName(
                ACCOUNT_ID, new StoreOperatorAccountUpdateRequest("새상호명"));

        // then
        assertThat(response.displayName()).isEqualTo("새상호명");
    }
}
