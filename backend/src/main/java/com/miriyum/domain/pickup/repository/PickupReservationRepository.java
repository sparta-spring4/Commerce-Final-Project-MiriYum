package com.miriyum.domain.pickup.repository;

import com.miriyum.domain.pickup.entity.PickupReservation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;
import com.miriyum.domain.pickup.entity.PickupStatus;

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

    Page<PickupReservation> findAllByStoreId(long storeId, Pageable pageable);

    Page<PickupReservation> findAllByStoreIdAndPickupDate(
            long storeId, LocalDate pickupDate, Pageable pageable);

    Page<PickupReservation> findAllByStoreIdAndStatus(
            long storeId, PickupStatus status, Pageable pageable);

    Page<PickupReservation> findAllByStoreIdAndPickupDateAndStatus(
            long storeId,
            LocalDate pickupDate,
            PickupStatus status,
            Pageable pageable
    );

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
