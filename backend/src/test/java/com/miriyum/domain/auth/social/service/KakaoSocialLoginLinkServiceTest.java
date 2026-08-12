package com.miriyum.domain.auth.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.entity.SocialLoginLink;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.enums.SocialLoginProvider;
import com.miriyum.domain.auth.social.repository.SocialLoginLinkRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KakaoSocialLoginLinkServiceTest {

    @Mock
    private SocialLoginLinkRepository socialLoginLinkRepository;

    @Mock
    private KakaoIdentityFingerprintGenerator fingerprintGenerator;

    @InjectMocks
    private KakaoSocialLoginLinkService linkService;

    @Captor
    private ArgumentCaptor<SocialLoginLink> linkCaptor;

    @Test
    @DisplayName("같은 계정 유형에 처음 연결한 카카오 식별자는 현재 계정에 저장한다")
    void linksNewKakaoIdentity() {
        given(fingerprintGenerator.generate("kakao-subject")).willReturn("fingerprint");
        given(socialLoginLinkRepository.findLink(
                TokenNamespace.CONSUMER, SocialLoginProvider.KAKAO, "fingerprint"))
                .willReturn(Optional.empty());
        given(socialLoginLinkRepository.saveAndFlush(any(SocialLoginLink.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        KakaoLinkResult result = linkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject");

        assertThat(result).isEqualTo(KakaoLinkResult.CREATED);
        then(socialLoginLinkRepository).should().saveAndFlush(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getNamespace()).isEqualTo(TokenNamespace.CONSUMER);
        assertThat(linkCaptor.getValue().getAccountId()).isEqualTo(10L);
        assertThat(linkCaptor.getValue().getProviderSubjectFingerprint()).isEqualTo("fingerprint");
    }

    @Test
    @DisplayName("이미 같은 계정에 연결된 카카오 식별자는 멱등하게 처리한다")
    void keepsExistingLinkForSameAccount() {
        given(fingerprintGenerator.generate("kakao-subject")).willReturn("fingerprint");
        given(socialLoginLinkRepository.findLink(
                TokenNamespace.CONSUMER, SocialLoginProvider.KAKAO, "fingerprint"))
                .willReturn(Optional.of(SocialLoginLink.create(
                        TokenNamespace.CONSUMER, 10L, SocialLoginProvider.KAKAO, "fingerprint")));

        KakaoLinkResult result = linkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject");

        assertThat(result).isEqualTo(KakaoLinkResult.ALREADY_LINKED);
        then(socialLoginLinkRepository).should(never()).saveAndFlush(any(SocialLoginLink.class));
    }

    @Test
    @DisplayName("다른 계정에 연결된 카카오 식별자는 이동시키지 않고 거절한다")
    void rejectsKakaoIdentityLinkedToAnotherAccount() {
        given(fingerprintGenerator.generate("kakao-subject")).willReturn("fingerprint");
        given(socialLoginLinkRepository.findLink(
                TokenNamespace.CONSUMER, SocialLoginProvider.KAKAO, "fingerprint"))
                .willReturn(Optional.of(SocialLoginLink.create(
                        TokenNamespace.CONSUMER, 99L, SocialLoginProvider.KAKAO, "fingerprint")));

        assertThatThrownBy(() -> linkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject"))
                .isInstanceOf(ServiceException.class);

        then(socialLoginLinkRepository).should(never()).saveAndFlush(any(SocialLoginLink.class));
    }
}
