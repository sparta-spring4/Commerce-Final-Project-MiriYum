package com.miriyum.domain.pickup.repository;

import com.miriyum.domain.pickup.entity.PickupReservation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickupReservationRepository
        extends JpaRepository<PickupReservation, Long> {

    @Override
    @EntityGraph(attributePaths = "items")
    Optional<PickupReservation> findById(Long id);

    @EntityGraph(attributePaths = "items")
    Optional<PickupReservation> findByIdAndConsumerAccountId(
            Long id,
            long consumerAccountId
    );

    @EntityGraph(attributePaths = "items")
    Optional<PickupReservation> findByIdAndStoreId(Long id, long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select pickup
            from PickupReservation pickup
            where pickup.id = :id
              and pickup.consumerAccountId = :consumerAccountId
            """)
    Optional<PickupReservation> findByIdAndConsumerAccountIdForUpdate(
            @Param("id") Long id,
            @Param("consumerAccountId") long consumerAccountId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select pickup
            from PickupReservation pickup
            where pickup.id = :id
              and pickup.storeId = :storeId
            """)
    Optional<PickupReservation> findByIdAndStoreIdForUpdate(
            @Param("id") Long id,
            @Param("storeId") long storeId
    );
}
