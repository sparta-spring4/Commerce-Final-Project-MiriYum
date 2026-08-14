package com.miriyum.domain.platformoperator.entity;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPasswordState;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "platform_operator_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformOperatorAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "platform_operator_account_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlatformOperatorAccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "password_state", nullable = false, length = 20)
    private PlatformOperatorPasswordState passwordState;

    @Column(name = "temporary_password_expires_at")
    private Instant temporaryPasswordExpiresAt;

    @Column(name = "temporary_password_failure_count", nullable = false)
    private int temporaryPasswordFailureCount;

    @Column(name = "authority_version", nullable = false)
    private long authorityVersion;

    @Column(name = "session_version", nullable = false)
    private long sessionVersion;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public static PlatformOperatorAccount createTemporary(
            String email, String passwordHash, String displayName, Instant temporaryPasswordExpiresAt) {
        PlatformOperatorAccount account = new PlatformOperatorAccount();
        account.email = requireText(email, "email").toLowerCase(Locale.ROOT);
        account.passwordHash = requireText(passwordHash, "passwordHash");
        account.displayName = requireText(displayName, "displayName");
        account.status = PlatformOperatorAccountStatus.ACTIVE;
        account.passwordState = PlatformOperatorPasswordState.TEMPORARY;
        account.temporaryPasswordExpiresAt = Objects.requireNonNull(
                temporaryPasswordExpiresAt, "temporaryPasswordExpiresAt must not be null");
        account.authorityVersion = 1L;
        account.sessionVersion = 1L;
        return account;
    }

    public void changeInitialPassword(String newPasswordHash) {
        if (passwordState != PlatformOperatorPasswordState.TEMPORARY) {
            throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
        }
        passwordHash = requireText(newPasswordHash, "newPasswordHash");
        passwordState = PlatformOperatorPasswordState.ACTIVE;
        temporaryPasswordExpiresAt = null;
        temporaryPasswordFailureCount = 0;
        sessionVersion++;
    }

    public void recordTemporaryPasswordFailure() {
        temporaryPasswordFailureCount++;
    }

    public boolean canUseTemporaryPassword(Instant now, int maximumFailures) {
        return passwordState == PlatformOperatorPasswordState.TEMPORARY
                && status == PlatformOperatorAccountStatus.ACTIVE
                && temporaryPasswordExpiresAt != null
                && Objects.requireNonNull(now, "now must not be null").isBefore(temporaryPasswordExpiresAt)
                && temporaryPasswordFailureCount < maximumFailures;
    }

    public void advanceAuthorityVersion() {
        authorityVersion++;
        sessionVersion++;
    }

    public void advanceSessionVersion() {
        sessionVersion++;
    }

    public void suspend() {
        if (status != PlatformOperatorAccountStatus.SUSPENDED) {
            status = PlatformOperatorAccountStatus.SUSPENDED;
            sessionVersion++;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
