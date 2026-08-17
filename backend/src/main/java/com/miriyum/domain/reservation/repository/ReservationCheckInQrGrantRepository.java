package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCheckInQrGrant;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 예약별 current QR grant의 조회·잠금·저장 경계다. */
public interface ReservationCheckInQrGrantRepository
        extends Repository<ReservationCheckInQrGrant, Long> {

    ReservationCheckInQrGrant save(ReservationCheckInQrGrant grant);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select grant from ReservationCheckInQrGrant grant
            where grant.reservationId = :reservationId
            """)
    Optional<ReservationCheckInQrGrant> findByReservationIdForUpdate(
            @Param("reservationId") Long reservationId
    );

    @Query(
            value = """
                    SELECT reservation_id
                    FROM reservation_check_in_qr_grants
                    WHERE token_digest = :tokenDigest
                    """,
            nativeQuery = true
    )
    Optional<Long> findReservationIdByTokenDigest(@Param("tokenDigest") byte[] tokenDigest);
}
