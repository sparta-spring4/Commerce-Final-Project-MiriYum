package com.miriyum.domain.auth.social.entity;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.enums.SocialLoginProvider;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계정 유형별 외부 로그인 연결이다.
 *
 * <p>외부 제공자의 원문 식별자는 저장하지 않고 서버 비밀키로 계산한 fingerprint만 보관한다.
 * 일반 사용자와 매장 운영자는 namespace가 다르므로 같은 카카오 계정을 각각 하나씩 연결할 수 있다.</p>
 */
@Entity
@Table(name = "social_login_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialLoginLink extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "social_login_link_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "namespace", nullable = false, length = 30)
    private TokenNamespace namespace;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30)
    private SocialLoginProvider provider;

    @Column(name = "provider_subject_fingerprint", nullable = false, length = 64)
    private String providerSubjectFingerprint;

    private SocialLoginLink(
            TokenNamespace namespace,
            Long accountId,
            SocialLoginProvider provider,
            String providerSubjectFingerprint
    ) {
        this.namespace = Objects.requireNonNull(namespace);
        this.accountId = Objects.requireNonNull(accountId);
        this.provider = Objects.requireNonNull(provider);
        this.providerSubjectFingerprint = Objects.requireNonNull(providerSubjectFingerprint);
    }

    public static SocialLoginLink create(
            TokenNamespace namespace,
            Long accountId,
            SocialLoginProvider provider,
            String providerSubjectFingerprint
    ) {
        return new SocialLoginLink(namespace, accountId, provider, providerSubjectFingerprint);
    }
}
