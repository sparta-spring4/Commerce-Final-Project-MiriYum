package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationDepositCauseAudit;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Append-only persistence boundary for deposit cause evidence. */
public interface ReservationDepositCauseAuditRepository
        extends Repository<ReservationDepositCauseAudit, Long> {

    ReservationDepositCauseAudit save(ReservationDepositCauseAudit audit);

    Optional<ReservationDepositCauseAudit>
            findByReservationDepositProcessIdAndCauseCode(long processId, String causeCode);
}
