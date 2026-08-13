package com.miriyum.domain.auth.social.repository;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.enums.SocialLoginProvider;
import com.miriyum.domain.auth.social.entity.SocialLoginLink;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 계정 유형별 카카오 식별자 연결을 조회·저장한다. */
public interface SocialLoginLinkRepository extends JpaRepository<SocialLoginLink, Long> {

    @Query("""
            select link
            from SocialLoginLink link
            where link.namespace = :namespace
              and link.provider = :provider
              and link.fingerprintKeyVersion = :fingerprintKeyVersion
              and link.providerSubjectFingerprint = :fingerprint
            """)
    Optional<SocialLoginLink> findLink(
            @Param("namespace") TokenNamespace namespace,
            @Param("provider") SocialLoginProvider provider,
            @Param("fingerprintKeyVersion") String fingerprintKeyVersion,
            @Param("fingerprint") String fingerprint
    );

    @Query("""
            select link
            from SocialLoginLink link
            where link.namespace = :namespace
              and link.accountId = :accountId
              and link.provider = :provider
            """)
    Optional<SocialLoginLink> findAccountLink(
            @Param("namespace") TokenNamespace namespace,
            @Param("accountId") Long accountId,
            @Param("provider") SocialLoginProvider provider
    );

    /** 고유 제약 충돌은 무해한 동일 행 갱신으로 처리하고, 다른 DB 오류는 그대로 전파한다. */
    @Modifying
    @Query(value = """
            INSERT INTO social_login_links (
                namespace,
                account_id,
                provider,
                fingerprint_key_version,
                provider_subject_fingerprint,
                created_at,
                updated_at
            ) VALUES (
                :namespace,
                :accountId,
                :provider,
                :fingerprintKeyVersion,
                :fingerprint,
                CURRENT_TIMESTAMP(6),
                CURRENT_TIMESTAMP(6)
            )
            ON DUPLICATE KEY UPDATE
                social_login_link_id = social_login_link_id
            """, nativeQuery = true)
    void insertIfAbsent(
            @Param("namespace") String namespace,
            @Param("accountId") Long accountId,
            @Param("provider") String provider,
            @Param("fingerprintKeyVersion") String fingerprintKeyVersion,
            @Param("fingerprint") String fingerprint
    );

    /**
     * 이전 키로 조회한 동일 연결만 현재 키 fingerprint로 바꾼다.
     *
     * <p>현재 fingerprint가 이미 다른 행에 연결된 경우에는 갱신하지 않아 고유 제약 예외를 만들지 않는다.</p>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE social_login_links target
            LEFT JOIN social_login_links conflict
              ON conflict.namespace = target.namespace
             AND conflict.provider = target.provider
             AND conflict.fingerprint_key_version = :activeKeyVersion
             AND conflict.provider_subject_fingerprint = :activeFingerprint
             AND conflict.social_login_link_id <> target.social_login_link_id
            SET target.fingerprint_key_version = :activeKeyVersion,
                target.provider_subject_fingerprint = :activeFingerprint,
                target.updated_at = CURRENT_TIMESTAMP(6)
            WHERE target.social_login_link_id = :linkId
              AND target.fingerprint_key_version = :previousKeyVersion
              AND target.provider_subject_fingerprint = :previousFingerprint
              AND conflict.social_login_link_id IS NULL
            """, nativeQuery = true)
    int migrateFingerprint(
            @Param("linkId") Long linkId,
            @Param("activeKeyVersion") String activeKeyVersion,
            @Param("activeFingerprint") String activeFingerprint,
            @Param("previousKeyVersion") String previousKeyVersion,
            @Param("previousFingerprint") String previousFingerprint
    );

}
