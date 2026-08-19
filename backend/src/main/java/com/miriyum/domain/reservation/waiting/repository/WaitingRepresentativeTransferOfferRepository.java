package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingRepresentativeTransferOffer;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitingRepresentativeTransferOfferRepository
        extends JpaRepository<WaitingRepresentativeTransferOffer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WaitingRepresentativeTransferOffer> findByActiveTeamKey(long waitingTeamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select offer from WaitingRepresentativeTransferOffer offer where offer.id = :id")
    Optional<WaitingRepresentativeTransferOffer> findByIdForUpdate(@Param("id") long id);
}
