package com.miriyum.domain.platformoperator.entity;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
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
@Table(name = "platform_operator_permission_grants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformOperatorPermissionGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "platform_operator_permission_grant_id")
    private Long id;

    @Column(name = "platform_operator_account_id", nullable = false)
    private Long platformOperatorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 70)
    private PlatformOperatorPermission permission;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    public static PlatformOperatorPermissionGrant create(
            Long platformOperatorAccountId,
            PlatformOperatorPermission permission,
            Instant grantedAt
    ) {
        PlatformOperatorPermissionGrant grant = new PlatformOperatorPermissionGrant();
        grant.platformOperatorAccountId = Objects.requireNonNull(platformOperatorAccountId);
        grant.permission = Objects.requireNonNull(permission);
        grant.grantedAt = Objects.requireNonNull(grantedAt);
        return grant;
    }
}
