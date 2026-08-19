package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingPartyInvitation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitingPartyInvitationRepository
        extends JpaRepository<WaitingPartyInvitation, Long> {

    Optional<WaitingPartyInvitation> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from WaitingPartyInvitation invitation where invitation.id = :id")
    Optional<WaitingPartyInvitation> findByIdForUpdate(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from WaitingPartyInvitation invitation where invitation.tokenHash = :tokenHash")
    Optional<WaitingPartyInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
