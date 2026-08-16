package com.miriyum.domain.pickup.repository;

import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.entity.PickupStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickupReservationRepository
        extends JpaRepository<PickupReservation, Long> {

    long countByStoreIdAndStatusAndPickupAtGreaterThanEqual(
            long storeId,
            PickupStatus status,
            Instant pickupAt
    );

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

    @EntityGraph(attributePaths = "items")
    @Query("select distinct pickup from PickupReservation pickup where pickup.id in :ids")
    List<PickupReservation> findAllWithItemsByIdIn(@Param("ids") List<Long> ids);

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
