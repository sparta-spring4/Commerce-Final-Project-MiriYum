package com.miriyum.domain.platformoperator.entity;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "platform_operator_role_grants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformOperatorRoleGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "platform_operator_role_grant_id")
    private Long id;

    @Column(name = "platform_operator_account_id", nullable = false)
    private Long platformOperatorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PlatformOperatorRole role;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    public static PlatformOperatorRoleGrant create(
            Long platformOperatorAccountId,
            PlatformOperatorRole role,
            Instant grantedAt
    ) {
        PlatformOperatorRoleGrant grant = new PlatformOperatorRoleGrant();
        grant.platformOperatorAccountId = Objects.requireNonNull(platformOperatorAccountId);
        grant.role = Objects.requireNonNull(role);
        grant.grantedAt = Objects.requireNonNull(grantedAt);
        return grant;
    }
}
