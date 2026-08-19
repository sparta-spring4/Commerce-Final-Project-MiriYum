package com.miriyum.domain.menuhold.repository;

import com.miriyum.domain.menuhold.entity.MenuHoldTransitionAudit;
import com.miriyum.domain.menuhold.entity.MenuHoldStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MenuHoldTransitionAuditRepository
        extends Repository<MenuHoldTransitionAudit, Long> {

    MenuHoldTransitionAudit save(MenuHoldTransitionAudit audit);

    List<MenuHoldTransitionAudit> findByMenuHold_IdAndOccurredAtLessThanEqualOrderByResultVersionAsc(
            long menuHoldId,
            Instant asOf);

    Optional<MenuHoldTransitionAudit> findFirstByMenuHold_IdOrderByResultVersionAsc(long menuHoldId);

    @Query("""
            select audit
              from MenuHoldTransitionAudit audit
              join audit.menuHold hold
             where audit.occurredAt between :changedFrom and :changedTo
               and (:storeId is null or hold.storeId = :storeId)
               and (:allStatuses = true or audit.afterStatus in :statuses)
               and (
                    :afterChangedAt is null
                    or audit.occurredAt < :afterChangedAt
                    or (
                        audit.occurredAt = :afterChangedAt
                        and case
                            when audit.reservationHoldId is not null
                                then concat('reservation-hold:', cast(audit.reservationHoldId as string))
                            else concat('reservation:', cast(audit.reservationId as string))
                            end < :afterCaseId
                    )
               )
               and not exists (
                    select newer.id
                      from MenuHoldTransitionAudit newer
                     where newer.menuHold.id = audit.menuHold.id
                       and newer.occurredAt between :changedFrom and :changedTo
                       and (:allStatuses = true or newer.afterStatus in :statuses)
                       and (
                            newer.occurredAt > audit.occurredAt
                            or (newer.occurredAt = audit.occurredAt and newer.id > audit.id)
                       )
               )
             order by audit.occurredAt desc,
                      case
                          when audit.reservationHoldId is not null
                              then concat('reservation-hold:', cast(audit.reservationHoldId as string))
                          else concat('reservation:', cast(audit.reservationId as string))
                          end desc,
                      audit.id desc
            """)
    List<MenuHoldTransitionAudit> findMonitoringChanges(
            @Param("changedFrom") Instant changedFrom,
            @Param("changedTo") Instant changedTo,
            @Param("storeId") Long storeId,
            @Param("allStatuses") boolean allStatuses,
            @Param("statuses") Set<MenuHoldStatus> statuses,
            @Param("afterChangedAt") Instant afterChangedAt,
            @Param("afterCaseId") String afterCaseId,
            Pageable pageable);

    List<MenuHoldTransitionAudit> findByMenuHold_IdInOrderByMenuHold_IdAscResultVersionAsc(
            List<Long> menuHoldIds);
}
