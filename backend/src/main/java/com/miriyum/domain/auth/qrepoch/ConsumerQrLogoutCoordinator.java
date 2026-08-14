package com.miriyum.domain.auth.qrepoch;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Consumer Auth 로그아웃 전용 조정자다. QR 발급·검증 소비자가 의존하는 공개 reader 계약과
 * Refresh family/QR epoch mutation 경계를 분리한다.
 */
@Service
public class ConsumerQrLogoutCoordinator {

    private final ConsumerQrEpochStore store;
    private final Clock clock;

    public ConsumerQrLogoutCoordinator(ConsumerQrEpochStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void advanceForLogout(
            TokenNamespace namespace,
            ParsedToken refreshToken,
            String rawRefreshToken,
            boolean corroboratingSubjectMismatch
    ) {
        ConsumerQrEpochAdvanceResult result = store.advanceForLogout(
                namespace, refreshToken, rawRefreshToken, corroboratingSubjectMismatch, clock.instant());
        if (result.status() == ConsumerQrEpochAdvanceResult.Status.SUBJECT_MISMATCH) {
            throw new ServiceException(AuthErrorCode.ACCESS_REFRESH_SUBJECT_MISMATCH);
        }
    }
}
