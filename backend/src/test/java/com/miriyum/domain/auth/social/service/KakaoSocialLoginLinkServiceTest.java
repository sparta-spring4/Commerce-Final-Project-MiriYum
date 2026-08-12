package com.miriyum.domain.auth.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.dto.KakaoIdentityFingerprint;
import com.miriyum.domain.auth.social.entity.SocialLoginLink;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.enums.SocialLoginProvider;
import com.miriyum.domain.auth.social.repository.SocialLoginLinkRepository;
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
class KakaoSocialLoginLinkServiceTest {

    private static final KakaoIdentityFingerprint ACTIVE = new KakaoIdentityFingerprint("v2", "active-fingerprint");

    @Mock
    private SocialLoginLinkRepository socialLoginLinkRepository;

    @Mock
    private KakaoIdentityFingerprintGenerator fingerprintGenerator;

    @InjectMocks
    private KakaoSocialLoginLinkService linkService;

    @Test
    @DisplayName("처음 연결한 카카오 식별자는 현재 계정에 연결한다")
    void linksNewKakaoIdentity() {
        given(fingerprintGenerator.generateActive("kakao-subject")).willReturn(ACTIVE);
        givenFindLink(ACTIVE, Optional.empty(), Optional.of(link(10L, ACTIVE)));

        KakaoLinkResult result = linkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject");

        assertThat(result).isEqualTo(KakaoLinkResult.CREATED);
        then(socialLoginLinkRepository).should().insertIfAbsent(
                TokenNamespace.CONSUMER.name(),
                10L,
                SocialLoginProvider.KAKAO.name(),
                ACTIVE.keyVersion(),
                ACTIVE.value());
    }

    @Test
    @DisplayName("같은 계정의 같은 카카오 식별자 연결은 멱등하게 처리한다")
    void keepsExistingLinkForSameAccount() {
        given(fingerprintGenerator.generateActive("kakao-subject")).willReturn(ACTIVE);
        givenFindLink(ACTIVE, Optional.of(link(10L, ACTIVE)));

        KakaoLinkResult result = linkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject");

        assertThat(result).isEqualTo(KakaoLinkResult.ALREADY_LINKED);
        then(socialLoginLinkRepository).should(never()).insertIfAbsent(
                TokenNamespace.CONSUMER.name(),
                10L,
                SocialLoginProvider.KAKAO.name(),
                ACTIVE.keyVersion(),
                ACTIVE.value());
    }

    @Test
    @DisplayName("다른 계정에 연결된 카카오 식별자는 거절한다")
    void rejectsKakaoIdentityLinkedToAnotherAccount() {
        given(fingerprintGenerator.generateActive("kakao-subject")).willReturn(ACTIVE);
        givenFindLink(ACTIVE, Optional.of(link(99L, ACTIVE)));

        assertThatThrownBy(() -> linkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.KAKAO_ALREADY_LINKED));
    }

    @Test
    @DisplayName("동시 연결로 다른 계정에 연결된 카카오 식별자는 거절한다")
    void rejectsConcurrentlyCreatedLinkForAnotherAccount() {
        given(fingerprintGenerator.generateActive("kakao-subject")).willReturn(ACTIVE);
        givenFindLink(ACTIVE, Optional.empty(), Optional.of(link(99L, ACTIVE)));

        assertThatThrownBy(() -> linkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.KAKAO_ALREADY_LINKED));
    }

    @Test
    @DisplayName("같은 계정에 다른 카카오 식별자를 추가 연결하면 거절한다")
    void rejectsDifferentKakaoIdentityForAccountWithExistingLink() {
        given(fingerprintGenerator.generateActive("kakao-subject")).willReturn(ACTIVE);
        givenFindLink(ACTIVE, Optional.empty(), Optional.empty());
        given(socialLoginLinkRepository.findAccountLink(
                TokenNamespace.CONSUMER, 10L, SocialLoginProvider.KAKAO))
                .willReturn(Optional.of(link(10L, new KakaoIdentityFingerprint("v1", "other-fingerprint"))));

        assertThatThrownBy(() -> linkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.KAKAO_ALREADY_LINKED));
    }

    @Test
    @DisplayName("이전 fingerprint 키로 찾은 연결은 현재 키 버전으로 갱신한다")
    void keepsPreviousFingerprintForMixedVersionDeployment() {
        KakaoIdentityFingerprint previous = new KakaoIdentityFingerprint("v1", "previous-fingerprint");
        SocialLoginLink legacyLink = link(10L, previous);
        ReflectionTestUtils.setField(legacyLink, "id", 1L);
        given(fingerprintGenerator.generateActive("kakao-subject")).willReturn(ACTIVE);
        given(fingerprintGenerator.generatePrevious("kakao-subject")).willReturn(Optional.of(previous));
        givenFindLink(ACTIVE, Optional.empty());
        givenFindLink(previous, Optional.of(legacyLink));

        Long accountId = linkService.findLinkedAccountId(TokenNamespace.CONSUMER, "kakao-subject");

        assertThat(accountId).isEqualTo(10L);
    }

    @Test
    @DisplayName("이전 키로 연결된 같은 계정은 키 교체 중에도 새 연결을 만들지 않는다")
    void keepsExistingLinkFoundWithPreviousFingerprintKey() {
        KakaoIdentityFingerprint previous = new KakaoIdentityFingerprint("v1", "previous-fingerprint");
        SocialLoginLink legacyLink = link(10L, previous);
        ReflectionTestUtils.setField(legacyLink, "id", 1L);
        given(fingerprintGenerator.generateActive("kakao-subject")).willReturn(ACTIVE);
        given(fingerprintGenerator.generatePrevious("kakao-subject")).willReturn(Optional.of(previous));
        givenFindLink(ACTIVE, Optional.empty());
        givenFindLink(previous, Optional.of(legacyLink));

        KakaoLinkResult result = linkService.link(TokenNamespace.CONSUMER, 10L, "kakao-subject");

        assertThat(result).isEqualTo(KakaoLinkResult.ALREADY_LINKED);
        then(socialLoginLinkRepository).should(never()).insertIfAbsent(
                TokenNamespace.CONSUMER.name(),
                10L,
                SocialLoginProvider.KAKAO.name(),
                ACTIVE.keyVersion(),
                ACTIVE.value());
    }

    @SafeVarargs
    private final void givenFindLink(KakaoIdentityFingerprint fingerprint, Optional<SocialLoginLink>... results) {
        var stubbing = given(socialLoginLinkRepository.findLink(
                TokenNamespace.CONSUMER,
                SocialLoginProvider.KAKAO,
                fingerprint.keyVersion(),
                fingerprint.value()));
        for (Optional<SocialLoginLink> result : results) {
            stubbing = stubbing.willReturn(result);
        }
    }

    private SocialLoginLink link(Long accountId, KakaoIdentityFingerprint fingerprint) {
        return SocialLoginLink.create(
                TokenNamespace.CONSUMER,
                accountId,
                SocialLoginProvider.KAKAO,
                fingerprint.keyVersion(),
                fingerprint.value());
    }
}
