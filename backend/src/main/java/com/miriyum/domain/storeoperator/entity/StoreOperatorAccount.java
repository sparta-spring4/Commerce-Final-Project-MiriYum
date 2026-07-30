package com.miriyum.domain.storeoperator.entity;

import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 매장 운영자 계정이다. 일반 사용자 계정과 물리적으로 분리된 별도 테이블·기본 키를 사용한다.
 *
 * <p>{@code phone}은 본인확인 제공업체가 선정되어 실제 전화번호를 해석하는 어댑터가 붙기 전까지
 * 채우지 않는다(BLOCKED, null).</p>
 */
@Entity
@Table(name = "store_operator_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class StoreOperatorAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_operator_account_id")
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
    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StoreOperatorAccountStatus status;

    public static StoreOperatorAccount create(String email, String passwordHash, String displayName) {
        StoreOperatorAccount account = new StoreOperatorAccount(email, passwordHash, displayName);
        account.status = StoreOperatorAccountStatus.ACTIVE;
        return account;
    }

    public void changeDisplayName(String newDisplayName) {
        this.displayName = newDisplayName;
    }
}
