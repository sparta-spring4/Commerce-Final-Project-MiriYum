package com.miriyum.domain.auth.social.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.dto.KakaoIdentityFingerprint;
import com.miriyum.domain.auth.social.entity.SocialLoginLink;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.enums.SocialLoginProvider;
import com.miriyum.domain.auth.social.repository.SocialLoginLinkRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 카카오 식별자를 계정 유형별 한 계정에만 멱등하게 연결한다. */
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public KakaoLinkResult link(TokenNamespace namespace, Long accountId, String kakaoSubject) {
        KakaoIdentityFingerprint active = fingerprintGenerator.generateActive(kakaoSubject);
        SocialLoginLink activeLink = findLink(namespace, active);
        if (activeLink != null) {
            return resultForExisting(activeLink, accountId);
        }

        SocialLoginLink previousLink = findPreviousLink(namespace, kakaoSubject);
        if (previousLink != null) {
            return resultForExisting(previousLink, accountId);
        }
        return findOrCreate(namespace, accountId, active);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public KakaoLinkResult linkFingerprint(
            TokenNamespace namespace,
            Long accountId,
            KakaoIdentityFingerprint fingerprint
    ) {
        return findOrCreate(namespace, accountId, fingerprint);
    }

    @Transactional
    public Long findLinkedAccountId(TokenNamespace namespace, String kakaoSubject) {
        KakaoIdentityFingerprint active = fingerprintGenerator.generateActive(kakaoSubject);
        SocialLoginLink activeLink = findLink(namespace, active);
        if (activeLink != null) {
            return activeLink.getAccountId();
        }

        SocialLoginLink previousLink = findPreviousLink(namespace, kakaoSubject);
        if (previousLink == null) {
            return null;
        }
        return previousLink.getAccountId();
    }

    private SocialLoginLink findPreviousLink(TokenNamespace namespace, String kakaoSubject) {
        return fingerprintGenerator.generatePrevious(kakaoSubject)
                .map(previous -> findLink(namespace, previous))
                .orElse(null);
    }

    private KakaoLinkResult findOrCreate(
            TokenNamespace namespace,
            Long accountId,
            KakaoIdentityFingerprint fingerprint
    ) {
        socialLoginLinkRepository.insertIfAbsent(
                namespace.name(),
                accountId,
                SocialLoginProvider.KAKAO.name(),
                fingerprint.keyVersion(),
                fingerprint.value());
        SocialLoginLink linkedSubject = findLink(namespace, fingerprint);
        if (linkedSubject != null) {
            if (!linkedSubject.getAccountId().equals(accountId)) {
                throw new ServiceException(AuthErrorCode.KAKAO_ALREADY_LINKED);
            }
            return KakaoLinkResult.CREATED;
        }

        if (socialLoginLinkRepository
                .findAccountLink(namespace, accountId, SocialLoginProvider.KAKAO)
                .isPresent()) {
            throw new ServiceException(AuthErrorCode.KAKAO_ALREADY_LINKED);
        }
        throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    private SocialLoginLink findLink(TokenNamespace namespace, KakaoIdentityFingerprint fingerprint) {
        return socialLoginLinkRepository.findLink(
                        namespace,
                        SocialLoginProvider.KAKAO,
                        fingerprint.keyVersion(),
                        fingerprint.value())
                .orElse(null);
    }

    private KakaoLinkResult resultForExisting(SocialLoginLink existing, Long accountId) {
        if (existing.getAccountId().equals(accountId)) {
            return KakaoLinkResult.ALREADY_LINKED;
        }
        throw new ServiceException(AuthErrorCode.KAKAO_ALREADY_LINKED);
    }
}
