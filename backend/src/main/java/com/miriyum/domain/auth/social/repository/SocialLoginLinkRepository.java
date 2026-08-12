package com.miriyum.domain.auth.social.repository;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.enums.SocialLoginProvider;
import com.miriyum.domain.auth.social.entity.SocialLoginLink;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 계정 유형별 카카오 식별자 연결을 조회·저장한다. */
public interface SocialLoginLinkRepository extends JpaRepository<SocialLoginLink, Long> {

    @Query("""
            select link
            from SocialLoginLink link
            where link.namespace = :namespace
              and link.provider = :provider
              and link.providerSubjectFingerprint = :fingerprint
            """)
    Optional<SocialLoginLink> findLink(
            @Param("namespace") TokenNamespace namespace,
            @Param("provider") SocialLoginProvider provider,
            @Param("fingerprint") String fingerprint
    );
}
