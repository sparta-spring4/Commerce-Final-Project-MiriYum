package com.miriyum.domain.reservation.service;

import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochService;
import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochSnapshot;
import com.miriyum.domain.reservation.dto.response.ReservationCheckInQrGrantResponse;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** transaction 밖 credential 생성·Auth epoch 캡처와 grant 저장 결과를 조합한다. */
@Service
public class ReservationCheckInQrGrantCommandFacade {

    private final ReservationCheckInQrTokenService tokenService;
    private final ConsumerQrEpochService qrEpochService;
    private final ReservationCheckInQrGrantService grantService;
    private final Clock clock;

    public ReservationCheckInQrGrantCommandFacade(
            ReservationCheckInQrTokenService tokenService,
            ConsumerQrEpochService qrEpochService,
            ReservationCheckInQrGrantService grantService,
            Clock clock
    ) {
        this.tokenService = tokenService;
        this.qrEpochService = qrEpochService;
        this.grantService = grantService;
        this.clock = clock;
    }

    /** 매 호출 새 raw credential을 만들고 성공한 발급 응답에서만 한 번 반환한다. */
    public ReservationCheckInQrGrantResult issue(long consumerAccountId, long reservationId) {
        if (consumerAccountId <= 0 || reservationId <= 0) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        ReservationCheckInQrTokenService.GeneratedToken generated;
        try {
            generated = tokenService.generate();
        } catch (RuntimeException exception) {
            ServiceException unavailable = new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            unavailable.initCause(exception);
            throw unavailable;
        }
        ConsumerQrEpochSnapshot snapshot = qrEpochService.captureCurrent(consumerAccountId);
        Instant requestedAt = clock.instant();
        ReservationCheckInQrGrantService.IssuedGrant issued = grantService.issue(
                consumerAccountId,
                reservationId,
                generated.digest(),
                snapshot,
                requestedAt
        );
        return new ReservationCheckInQrGrantResult(
                201,
                ReservationCheckInQrGrantResponse.of(
                        issued.reservationId(),
                        generated.rawToken(),
                        issued.tokenVersion(),
                        issued.issuedAt(),
                        issued.expiresAt()
                )
        );
    }
}
