package com.miriyum.domain.storeoperator.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.service.KakaoSocialLoginLinkService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreOperatorKakaoLinkTransactionService {

    private final StoreOperatorAccountRepository storeOperatorAccountRepository;
    private final KakaoSocialLoginLinkService kakaoSocialLoginLinkService;

    public StoreOperatorKakaoLinkTransactionService(
            StoreOperatorAccountRepository storeOperatorAccountRepository,
            KakaoSocialLoginLinkService kakaoSocialLoginLinkService
    ) {
        this.storeOperatorAccountRepository = storeOperatorAccountRepository;
        this.kakaoSocialLoginLinkService = kakaoSocialLoginLinkService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public KakaoLinkResult linkActiveAccount(Long accountId, String kakaoSubject) {
        StoreOperatorAccount account = storeOperatorAccountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID));
        if (account.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        return kakaoSocialLoginLinkService.link(TokenNamespace.STORE_OPERATOR, accountId, kakaoSubject);
    }
}
