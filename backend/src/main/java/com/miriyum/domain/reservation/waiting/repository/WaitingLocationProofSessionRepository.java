package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitingLocationProofSessionRepository
        extends JpaRepository<WaitingLocationProofSession, String> {

    default Optional<WaitingLocationProofSession> findById(UUID id) {
        return findById(id.toString());
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proof from WaitingLocationProofSession proof where proof.id = :id")
    Optional<WaitingLocationProofSession> findByIdForUpdate(@Param("id") String id);

    default Optional<WaitingLocationProofSession> findByIdForUpdate(UUID id) {
        return findByIdForUpdate(id.toString());
    }
}
