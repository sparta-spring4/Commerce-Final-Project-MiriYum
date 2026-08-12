package com.miriyum.domain.consumer.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.service.KakaoSocialLoginLinkService;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsumerKakaoLinkTransactionService {

    private final ConsumerAccountRepository consumerAccountRepository;
    private final KakaoSocialLoginLinkService kakaoSocialLoginLinkService;

    public ConsumerKakaoLinkTransactionService(
            ConsumerAccountRepository consumerAccountRepository,
            KakaoSocialLoginLinkService kakaoSocialLoginLinkService
    ) {
        this.consumerAccountRepository = consumerAccountRepository;
        this.kakaoSocialLoginLinkService = kakaoSocialLoginLinkService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public KakaoLinkResult linkActiveAccount(Long accountId, String kakaoSubject) {
        ConsumerAccount account = consumerAccountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID));
        if (account.getStatus() != ConsumerAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        return kakaoSocialLoginLinkService.link(TokenNamespace.CONSUMER, accountId, kakaoSubject);
    }
}
