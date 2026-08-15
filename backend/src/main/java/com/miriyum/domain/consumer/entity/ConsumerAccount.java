package com.miriyum.domain.consumer.entity;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import com.miriyum.global.exception.ServiceException;

/**
 * 일반 사용자 계정이다. 매장 운영자 계정과 물리적으로 분리된 별도 테이블·기본 키를 사용한다.
 */
@Entity
@Table(name = "consumer_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsumerAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consumer_account_id")
    private Long id;

    @NonNull
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "phone", length = 512)
    private String phone;

    @Column(name = "reservation_contact_reference", length = 512, unique = true)
    private String reservationContactReference;

    @NonNull
    @Column(name = "name", nullable = false, length = 20)
    private String name;

    @Column(name = "nickname_changed_at")
    private LocalDateTime nicknameChangedAt;

    @Column(name = "password_reset_required", nullable = false)
    private boolean passwordResetRequired;

    @Column(name = "support_version", nullable = false)
    private long supportVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ConsumerAccountStatus status;

    private ConsumerAccount(String email, String passwordHash, String name) {
        this.email = java.util.Objects.requireNonNull(email);
        this.passwordHash = passwordHash;
        this.name = java.util.Objects.requireNonNull(name);
    }

    public static ConsumerAccount create(String email, String passwordHash, String name) {
        ConsumerAccount account = new ConsumerAccount(email, passwordHash, name);
        account.status = ConsumerAccountStatus.ACTIVE;
        return account;
    }

    public static ConsumerAccount createWithContact(
            String email,
            String passwordHash,
            String name,
            String phone,
            String reservationContactReference
    ) {
        ConsumerAccount account = create(email, passwordHash, name);
        account.registerContact(phone, reservationContactReference);
        return account;
    }

    public void registerContact(String normalizedPhone, String reservationContactReference) {
        this.phone = normalizedPhone;
        this.reservationContactReference = reservationContactReference;
    }

    public String getReservationContactReference() {
        return reservationContactReference;
    }

    public void changeName(String newName, LocalDateTime changedAt) {
        this.name = newName;
        this.nicknameChangedAt = changedAt;
    }

    public void assertSupportVersion(long expectedVersion) {
        if (supportVersion != expectedVersion) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
    }

    public void approveRecovery(String newEmail) {
        this.email = java.util.Objects.requireNonNull(newEmail);
        this.passwordResetRequired = true;
        this.supportVersion++;
    }

    public void replaceRecoveredPassword(String newPasswordHash) {
        if (!passwordResetRequired) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        this.passwordHash = java.util.Objects.requireNonNull(newPasswordHash);
        this.passwordResetRequired = false;
        this.supportVersion++;
    }

    public void applySupportSuspension() {
        this.status = ConsumerAccountStatus.SUSPENDED;
        this.supportVersion++;
    }

    public void clearSupportSuspension() {
        this.status = ConsumerAccountStatus.ACTIVE;
        this.supportVersion++;
    }

    public void advanceSupportVersion() { this.supportVersion++; }
}
