package com.miriyum.domain.auth.social.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.entity.SocialLoginLink;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.enums.SocialLoginProvider;
import com.miriyum.domain.auth.social.repository.SocialLoginLinkRepository;
import com.miriyum.global.exception.ServiceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 카카오 식별자를 계정 유형별로 한 계정에만 멱등하게 연결한다. */
@Service
public class KakaoSocialLoginLinkService {

    private final SocialLoginLinkRepository socialLoginLinkRepository;
    private final KakaoIdentityFingerprintGenerator fingerprintGenerator;

    public KakaoSocialLoginLinkService(
            SocialLoginLinkRepository socialLoginLinkRepository,
            KakaoIdentityFingerprintGenerator fingerprintGenerator
    ) {
        this.socialLoginLinkRepository = socialLoginLinkRepository;
        this.fingerprintGenerator = fingerprintGenerator;
    }

    @Transactional
    public KakaoLinkResult link(TokenNamespace namespace, Long accountId, String kakaoSubject) {
        String fingerprint = fingerprintGenerator.generate(kakaoSubject);
        return findOrCreate(namespace, accountId, fingerprint);
    }

    @Transactional
    public KakaoLinkResult linkFingerprint(TokenNamespace namespace, Long accountId, String fingerprint) {
        return findOrCreate(namespace, accountId, fingerprint);
    }

    @Transactional(readOnly = true)
    public Long findLinkedAccountId(TokenNamespace namespace, String kakaoSubject) {
        String fingerprint = fingerprintGenerator.generate(kakaoSubject);
        return socialLoginLinkRepository.findLink(
                        namespace, SocialLoginProvider.KAKAO, fingerprint)
                .map(SocialLoginLink::getAccountId)
                .orElse(null);
    }

    private KakaoLinkResult findOrCreate(TokenNamespace namespace, Long accountId, String fingerprint) {
        SocialLoginLink existing = socialLoginLinkRepository
                .findLink(
                        namespace, SocialLoginProvider.KAKAO, fingerprint)
                .orElse(null);
        if (existing != null) {
            return resultForExisting(existing, accountId);
        }

        try {
            socialLoginLinkRepository.saveAndFlush(SocialLoginLink.create(
                    namespace, accountId, SocialLoginProvider.KAKAO, fingerprint));
            return KakaoLinkResult.CREATED;
        } catch (DataIntegrityViolationException exception) {
            SocialLoginLink concurrent = socialLoginLinkRepository
                    .findLink(
                            namespace, SocialLoginProvider.KAKAO, fingerprint)
                    .orElseThrow(() -> exception);
            return resultForExisting(concurrent, accountId);
        }
    }

    private KakaoLinkResult resultForExisting(SocialLoginLink existing, Long accountId) {
        if (existing.getAccountId().equals(accountId)) {
            return KakaoLinkResult.ALREADY_LINKED;
        }
        throw new ServiceException(AuthErrorCode.KAKAO_ALREADY_LINKED);
    }
}
