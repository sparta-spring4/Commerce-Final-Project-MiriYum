package com.miriyum.domain.platformoperator.repository.membersupport;

import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberSanctionRepository extends JpaRepository<MemberSanction, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sanction from MemberSanction sanction where sanction.id = :id")
    Optional<MemberSanction> findByIdForUpdate(@Param("id") Long id);

    Optional<MemberSanction> findByPublicId(String publicId);

    @Query("select sanction from MemberSanction sanction where sanction.accountType = :accountType "
            + "and sanction.accountId = :accountId and sanction.status = 'APPLIED' "
            + "and sanction.appliedAt <= :now and (sanction.endsAt is null or sanction.endsAt > :now)")
    List<MemberSanction> findActive(@Param("accountType") com.miriyum.domain.auth.membersupport.MemberAccountType accountType,
                                    @Param("accountId") long accountId,
                                    @Param("now") LocalDateTime now);

    @Query(value = """
            select count(*) > 0 from member_sanctions
             where account_type = :#{#accountType.name()}
               and account_id = :accountId
               and level = 'FEATURE_RESTRICTION'
               and status = 'APPLIED'
               and (ends_at is null or ends_at > :now)
               and json_contains(restricted_features, json_quote(:feature))
            """, nativeQuery = true)
    boolean hasActiveFeatureRestriction(@Param("accountType") com.miriyum.domain.auth.membersupport.MemberAccountType accountType,
                                        @Param("accountId") long accountId,
                                        @Param("feature") String feature,
                                        @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sanction from MemberSanction sanction join fetch sanction.supportCase where sanction.publicId = :id")
    Optional<MemberSanction> findByPublicIdForUpdate(@Param("id") String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sanction from MemberSanction sanction where sanction.status = 'APPLIED' and sanction.endsAt <= :now")
    List<MemberSanction> findExpiredForUpdate(@Param("now") LocalDateTime now);

    @Query(value = """
            select count(*) from member_sanctions
             where account_type = :#{#accountType.name()}
               and account_id = :accountId
               and member_sanction_id <> :excludedId
               and level in ('TEMPORARY_SUSPENSION', 'PERMANENT_SUSPENSION')
               and status = 'APPLIED'
               and (ends_at is null or ends_at > :now)
            """, nativeQuery = true)
    long countOtherActiveSuspensions(
            @Param("accountType") com.miriyum.domain.auth.membersupport.MemberAccountType accountType,
            @Param("accountId") long accountId,
            @Param("excludedId") long excludedId,
            @Param("now") LocalDateTime now);
}
