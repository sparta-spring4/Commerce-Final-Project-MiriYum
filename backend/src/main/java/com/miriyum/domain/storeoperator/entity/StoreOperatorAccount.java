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
 * <p>1차 MVP에서는 회원가입 시 입력한 휴대전화를 실제 소유 인증이 완료된 것으로 간주해 저장한다.
 * 외부 본인확인 제공업체 연동은 후속 고도화 범위다.</p>
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
    @Column(name = "password_hash", nullable = false, length = 255)
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

    public static StoreOperatorAccount createWithContact(
            String email,
            String passwordHash,
            String displayName,
            String phone
    ) {
        StoreOperatorAccount account = create(email, passwordHash, displayName);
        account.registerContact(phone);
        return account;
    }

    public void registerContact(String normalizedPhone) {
        this.phone = normalizedPhone;
    }

    public void changeDisplayName(String newDisplayName) {
        this.displayName = newDisplayName;
    }
}
