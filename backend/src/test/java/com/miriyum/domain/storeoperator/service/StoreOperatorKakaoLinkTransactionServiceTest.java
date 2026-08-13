package com.miriyum.domain.storeoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.service.KakaoSocialLoginLinkService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreOperatorKakaoLinkTransactionServiceTest {

    @Mock
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Mock
    private KakaoSocialLoginLinkService kakaoSocialLoginLinkService;

    @InjectMocks
    private StoreOperatorKakaoLinkTransactionService transactionService;

    @Test
    @DisplayName("잠금 조회한 활성 매장 운영자 계정에 카카오를 연결한다")
    void linksActiveAccount() {
        StoreOperatorAccount account = StoreOperatorAccount.create(
                "operator@example.com", "{sha256-bcrypt}hash", "운영자 이름");
        given(storeOperatorAccountRepository.findByIdForUpdate(20L)).willReturn(Optional.of(account));
        given(kakaoSocialLoginLinkService.link(TokenNamespace.STORE_OPERATOR, 20L, "kakao-subject"))
                .willReturn(KakaoLinkResult.CREATED);

        KakaoLinkResult result = transactionService.linkActiveAccount(20L, "kakao-subject");

        assertThat(result).isEqualTo(KakaoLinkResult.CREATED);
    }

    @Test
    @DisplayName("잠금 조회 시 제한된 매장 운영자 계정은 카카오 연결을 거절한다")
    void rejectsRestrictedAccount() {
        StoreOperatorAccount account = StoreOperatorAccount.create(
                "operator@example.com", "{sha256-bcrypt}hash", "운영자 이름");
        ReflectionTestUtils.setField(account, "status", StoreOperatorAccountStatus.SUSPENDED);
        given(storeOperatorAccountRepository.findByIdForUpdate(20L)).willReturn(Optional.of(account));

        assertThatThrownBy(() -> transactionService.linkActiveAccount(20L, "kakao-subject"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
        then(kakaoSocialLoginLinkService).shouldHaveNoInteractions();
    }
}
