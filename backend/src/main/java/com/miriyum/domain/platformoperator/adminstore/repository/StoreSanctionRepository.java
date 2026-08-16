package com.miriyum.domain.platformoperator.adminstore.repository;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanction; import jakarta.persistence.LockModeType; import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface StoreSanctionRepository extends JpaRepository<StoreSanction,Long>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from StoreSanction s where s.id=:id and s.caseId=:caseId and s.storeId=:storeId")
 Optional<StoreSanction> findScopedForUpdate(@Param("id")long id,@Param("caseId")String caseId,@Param("storeId")long storeId);
 List<StoreSanction> findByCaseIdOrderByIdAsc(String caseId);
 List<StoreSanction> findByStoreIdInAndStatus(Collection<Long> storeIds,com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionStatus status);
 @Query("select s.id from StoreSanction s where s.type=:type and s.status=:status and s.endsAt <= :now order by s.id")
 List<Long> findDueExpiryIds(@Param("type")com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType type,
  @Param("status")com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionStatus status,
  @Param("now")java.time.Instant now,org.springframework.data.domain.Pageable pageable);
}
