package com.miriyum.domain.consumer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.service.KakaoSocialLoginLinkService;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
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
class ConsumerKakaoLinkTransactionServiceTest {

    @Mock
    private ConsumerAccountRepository consumerAccountRepository;

    @Mock
    private KakaoSocialLoginLinkService kakaoSocialLoginLinkService;

    @InjectMocks
    private ConsumerKakaoLinkTransactionService transactionService;

    @Test
    @DisplayName("잠금 조회한 활성 일반 사용자 계정에 카카오를 연결한다")
    void linksActiveAccount() {
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "{sha256-bcrypt}hash", "닉네임");
        given(consumerAccountRepository.findByIdForUpdate(10L)).willReturn(Optional.of(account));
        given(kakaoSocialLoginLinkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject"))
                .willReturn(KakaoLinkResult.CREATED);

        KakaoLinkResult result = transactionService.linkActiveAccount(10L, "kakao-subject");

        assertThat(result).isEqualTo(KakaoLinkResult.CREATED);
    }

    @Test
    @DisplayName("잠금 조회 시 제한된 일반 사용자 계정은 카카오 연결을 거절한다")
    void rejectsRestrictedAccount() {
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "{sha256-bcrypt}hash", "닉네임");
        ReflectionTestUtils.setField(account, "status", ConsumerAccountStatus.SUSPENDED);
        given(consumerAccountRepository.findByIdForUpdate(10L)).willReturn(Optional.of(account));

        assertThatThrownBy(() -> transactionService.linkActiveAccount(10L, "kakao-subject"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
        then(kakaoSocialLoginLinkService).shouldHaveNoInteractions();
    }
}
