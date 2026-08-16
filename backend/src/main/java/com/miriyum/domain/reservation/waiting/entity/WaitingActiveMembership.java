package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** 소비자 계정 전체에서 활성 웨이팅 한 건 제약을 소유한다. */
@Entity
@Table(
        name = "waiting_active_memberships",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_waiting_active_memberships_consumer_account",
                    columnNames = "consumer_account_id"
            ),
            @UniqueConstraint(
                    name = "uk_waiting_active_memberships_team",
                    columnNames = "waiting_team_id"
            )
        })
public class WaitingActiveMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_active_membership_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "consumer_account_id", nullable = false)
    private Long consumerAccountId;

    @Column(name = "waiting_team_id", nullable = false)
    private Long waitingTeamId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WaitingActiveMembership() {
    }

    private WaitingActiveMembership(
            long storeId,
            long consumerAccountId,
            long waitingTeamId,
            Instant createdAt
    ) {
        this.storeId = requirePositive(storeId, "storeId");
        this.consumerAccountId = requirePositive(consumerAccountId, "consumerAccountId");
        this.waitingTeamId = requirePositive(waitingTeamId, "waitingTeamId");
        this.createdAt = requireNonNull(createdAt, "createdAt");
    }

    /** 생성된 팀에 활성 중복 잠금을 연결한다. */
    public static WaitingActiveMembership create(
            long storeId,
            long consumerAccountId,
            long waitingTeamId,
            Instant createdAt
    ) {
        return new WaitingActiveMembership(storeId, consumerAccountId, waitingTeamId, createdAt);
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public Long getConsumerAccountId() {
        return consumerAccountId;
    }

    public Long getWaitingTeamId() {
        return waitingTeamId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
