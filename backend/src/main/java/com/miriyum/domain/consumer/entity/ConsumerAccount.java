package com.miriyum.domain.consumer.entity;

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
import lombok.RequiredArgsConstructor;

/**
 * 일반 사용자 계정이다. 매장 운영자 계정과 물리적으로 분리된 별도 테이블·기본 키를 사용한다.
 */
@Entity
@Table(name = "consumer_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ConsumerAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consumer_account_id")
    private Long id;

    @NonNull
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @NonNull
    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "phone", length = 512)
    private String phone;

    @NonNull
    @Column(name = "name", nullable = false, length = 20)
    private String name;

    @Column(name = "nickname_changed_at")
    private LocalDateTime nicknameChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ConsumerAccountStatus status;

    public static ConsumerAccount create(String email, String passwordHash, String name) {
        ConsumerAccount account = new ConsumerAccount(email, passwordHash, name);
        account.status = ConsumerAccountStatus.ACTIVE;
        return account;
    }

    public void changeName(String newName, LocalDateTime changedAt) {
        this.name = newName;
        this.nicknameChangedAt = changedAt;
    }
}
