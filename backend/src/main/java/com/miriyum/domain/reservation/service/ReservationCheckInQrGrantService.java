package com.miriyum.domain.reservation.service;

import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochSnapshot;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCheckInAudit;
import com.miriyum.domain.reservation.entity.ReservationCheckInQrGrant;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCheckInAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationCheckInQrGrantRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reservation 잠금 아래 current QR grant의 발급·회전과 최소 감사를 원자 저장한다. */
@Service
public class ReservationCheckInQrGrantService {

    private final ReservationRepository reservationRepository;
    private final ReservationCheckInQrGrantRepository grantRepository;
    private final ReservationCheckInAuditRepository auditRepository;
    private final Clock clock;

    public ReservationCheckInQrGrantService(
            ReservationRepository reservationRepository,
            ReservationCheckInQrGrantRepository grantRepository,
            ReservationCheckInAuditRepository auditRepository,
            Clock clock
    ) {
        this.reservationRepository = reservationRepository;
        this.grantRepository = grantRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    /** 본인 CONFIRMED 예약의 current grant를 새 version으로 교체한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public IssuedGrant issue(
            long consumerAccountId,
            long reservationId,
            byte[] tokenDigest,
            ConsumerQrEpochSnapshot epochSnapshot,
            Instant requestedAt
    ) {
        if (consumerAccountId <= 0
                || reservationId <= 0
                || tokenDigest == null
                || tokenDigest.length != 32
                || epochSnapshot == null
                || epochSnapshot.accountId() != consumerAccountId
                || requestedAt == null) {
            throw new IllegalArgumentException("valid QR grant issue arguments are required");
        }
        Reservation reservation = reservationRepository
                .findByIdAndConsumerAccountIdForUpdate(reservationId, consumerAccountId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND
                ));
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
        }

        ReservationCheckInQrGrant grant = grantRepository
                .findByReservationIdForUpdate(reservationId)
                .map(current -> {
                    current.rotate(tokenDigest, epochSnapshot, requestedAt);
                    return current;
                })
                .orElseGet(() -> ReservationCheckInQrGrant.issue(
                        reservationId,
                        tokenDigest,
                        epochSnapshot,
                        requestedAt
                ));
        ReservationCheckInQrGrant saved = grantRepository.save(grant);
        Instant occurredAt = clock.instant();
        auditRepository.save(ReservationCheckInAudit.recordGrantIssued(
                reservationId,
                reservation.getStoreId(),
                consumerAccountId,
                saved.getTokenVersion(),
                requestedAt,
                occurredAt,
                "reservation-qr-grant:" + reservationId + ":" + saved.getTokenVersion()
        ));
        return new IssuedGrant(
                reservationId,
                saved.getTokenVersion(),
                saved.getIssuedAt(),
                saved.getExpiresAt()
        );
    }

    /** raw token을 포함하지 않는 transaction 결과 metadata다. */
    public record IssuedGrant(
            long reservationId,
            long tokenVersion,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}
