package com.miriyum.domain.platformoperator.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "platform_operator_authority_guard")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformOperatorAuthorityGuard {
    @Id
    @Column(name = "guard_id")
    private Byte id;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
