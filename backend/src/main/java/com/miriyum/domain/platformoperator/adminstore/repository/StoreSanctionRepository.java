package com.miriyum.domain.platformoperator.adminstore.repository;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanction; import jakarta.persistence.LockModeType; import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface StoreSanctionRepository extends JpaRepository<StoreSanction,Long>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from StoreSanction s where s.id=:id and s.caseId=:caseId and s.storeId=:storeId")
 Optional<StoreSanction> findScopedForUpdate(@Param("id")long id,@Param("caseId")String caseId,@Param("storeId")long storeId);
 List<StoreSanction> findByCaseIdOrderByIdAsc(String caseId);
 List<StoreSanction> findByStoreIdInAndStatus(Collection<Long> storeIds,com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionStatus status);
}
