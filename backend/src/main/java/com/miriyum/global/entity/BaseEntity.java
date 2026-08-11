package com.miriyum.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 모든 entity가 공통으로 쓰는 생성·수정 시각이다. 실제 값은 {@code JpaAuditingConfig}가 등록한
 * {@code DateTimeProvider}가 채워주므로, 테스트에서는 그 Provider가 감싼 {@link java.time.Clock}을
 * 고정값으로 바꿔치기해 시스템 시각 의존 없이 검증할 수 있다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
