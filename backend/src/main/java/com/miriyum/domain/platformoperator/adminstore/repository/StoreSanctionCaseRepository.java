package com.miriyum.domain.platformoperator.adminstore.repository;

import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionCase;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.CaseStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreSanctionCaseRepository extends JpaRepository<StoreSanctionCase, Long> {
    Optional<StoreSanctionCase> findByPublicIdAndStoreId(String publicId, long storeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StoreSanctionCase c where c.publicId=:publicId and c.storeId=:storeId")
    Optional<StoreSanctionCase> findByPublicIdAndStoreIdForUpdate(@Param("publicId") String publicId,
                                                                  @Param("storeId") long storeId);
    @Query("select c.publicId from StoreSanctionCase c where c.storeId=:storeId and c.status not in :closed")
    List<String> findOpenPublicIdsByStoreIdAndStatusNotIn(@Param("storeId") long storeId,
                                                           @Param("closed") Collection<CaseStatus> closed);
    default List<String> findOpenPublicIdsByStoreId(long storeId) {
        return findOpenPublicIdsByStoreIdAndStatusNotIn(
                storeId, Set.of(CaseStatus.RESOLVED, CaseStatus.REJECTED));
    }
}
