package com.miriyum.domain.reservation.waiting.entity;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/** 매장과 KST 영업일에 귀속된 중앙 FIFO 웨이팅 팀 상태를 소유한다. */
@Entity
@Table(
        name = "waiting_teams",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_waiting_teams_store_date_sequence",
                columnNames = {"store_id", "business_date", "queue_sequence"}
        ))
public class WaitingTeam {

    private static final Duration ARRIVAL_WINDOW = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_team_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "consumer_account_id", nullable = false)
    private Long consumerAccountId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "party_size", nullable = false)
    private int partySize;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private WaitingSource source;

    @Column(name = "queue_sequence", nullable = false)
    private long queueSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WaitingTeamStatus status;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "called_at")
    private Instant calledAt;

    @Column(name = "arrival_deadline")
    private Instant arrivalDeadline;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "no_show_at")
    private Instant noShowAt;

    @Column(name = "closed_by_store_at")
    private Instant closedByStoreAt;

    protected WaitingTeam() {
    }

    private WaitingTeam(
            long storeId,
            long consumerAccountId,
            LocalDate businessDate,
            int partySize,
            WaitingSource source,
            long queueSequence,
            Instant createdAt
    ) {
        this.storeId = requirePositive(storeId, "storeId");
        this.consumerAccountId = requirePositive(consumerAccountId, "consumerAccountId");
        this.businessDate = requireNonNull(businessDate, "businessDate");
        this.partySize = requirePositive(partySize, "partySize");
        this.source = requireNonNull(source, "source");
        this.queueSequence = requirePositive(queueSequence, "queueSequence");
        this.status = WaitingTeamStatus.WAITING;
        this.createdAt = requireNonNull(createdAt, "createdAt");
    }

    /** 검증과 순번 할당이 끝난 활성 웨이팅 팀을 생성한다. */
    public static WaitingTeam create(
            long storeId,
            long consumerAccountId,
            LocalDate businessDate,
            int partySize,
            WaitingSource source,
            long queueSequence,
            Instant createdAt
    ) {
        return new WaitingTeam(
                storeId,
                consumerAccountId,
                businessDate,
                partySize,
                source,
                queueSequence,
                createdAt
        );
    }

    /** FIFO 선두인 대기 팀에 실제 호출을 한 번 기록한다. */
    public void call(long expectedVersion, Instant occurredAt) {
        requireVersion(expectedVersion);
        requireStatus(WaitingTeamStatus.WAITING);
        Instant transitionAt = requireNotBefore(occurredAt, createdAt);
        status = WaitingTeamStatus.CALLED;
        calledAt = transitionAt;
        arrivalDeadline = transitionAt.plus(ARRIVAL_WINDOW);
        version++;
    }

    /** 호출 제한 시각까지 확인된 현장 도착을 기록한다. */
    public void arrive(long expectedVersion, Instant occurredAt) {
        requireVersion(expectedVersion);
        requireStatus(WaitingTeamStatus.CALLED);
        Instant transitionAt = requireNotBefore(occurredAt, calledAt);
        if (transitionAt.isAfter(arrivalDeadline)) {
            throw invalidTransition();
        }
        status = WaitingTeamStatus.ARRIVED;
        arrivedAt = transitionAt;
        version++;
    }

    /** 도착 확인된 팀의 실제 입장을 종결 기록한다. */
    public void checkIn(long expectedVersion, Instant occurredAt) {
        requireVersion(expectedVersion);
        requireStatus(WaitingTeamStatus.ARRIVED);
        checkedInAt = requireNotBefore(occurredAt, arrivedAt);
        status = WaitingTeamStatus.CHECKED_IN;
        version++;
    }

    /** 대기·호출·도착 상태의 사용자 취소를 종결 기록한다. */
    public void cancel(long expectedVersion, Instant occurredAt) {
        requireVersion(expectedVersion);
        if (status != WaitingTeamStatus.WAITING
                && status != WaitingTeamStatus.CALLED
                && status != WaitingTeamStatus.ARRIVED) {
            throw invalidTransition();
        }
        cancelledAt = requireNotBefore(occurredAt, latestStateTimestamp());
        status = WaitingTeamStatus.CANCELLED;
        version++;
    }

    /** 도착 제한 시각이 지난 호출 팀을 미응답으로 종결한다. */
    public void markNoShow(long expectedVersion, Instant occurredAt) {
        requireVersion(expectedVersion);
        requireStatus(WaitingTeamStatus.CALLED);
        Instant transitionAt = requireNonNull(occurredAt, "occurredAt");
        if (transitionAt.isBefore(arrivalDeadline)) {
            throw invalidTransition();
        }
        noShowAt = transitionAt;
        status = WaitingTeamStatus.NO_SHOW;
        version++;
    }

    /** 대기·호출·도착 상태의 팀을 사용자 취소와 구분해 매장 종료한다. */
    public void closeByStore(long expectedVersion, Instant occurredAt) {
        requireVersion(expectedVersion);
        if (status != WaitingTeamStatus.WAITING
                && status != WaitingTeamStatus.CALLED
                && status != WaitingTeamStatus.ARRIVED) {
            throw invalidTransition();
        }
        closedByStoreAt = requireNotBefore(occurredAt, latestStateTimestamp());
        status = WaitingTeamStatus.CLOSED_BY_STORE;
        version++;
    }

    private void requireVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new ServiceException(ReservationErrorCode.WAITING_VERSION_CONFLICT);
        }
    }

    private void requireStatus(WaitingTeamStatus requiredStatus) {
        if (status != requiredStatus) {
            throw invalidTransition();
        }
    }

    private ServiceException invalidTransition() {
        return new ServiceException(ReservationErrorCode.WAITING_INVALID_TRANSITION);
    }

    private Instant latestStateTimestamp() {
        if (arrivedAt != null) {
            return arrivedAt;
        }
        if (calledAt != null) {
            return calledAt;
        }
        return createdAt;
    }

    private static Instant requireNotBefore(Instant occurredAt, Instant lowerBound) {
        Instant value = requireNonNull(occurredAt, "occurredAt");
        if (value.isBefore(lowerBound)) {
            throw new IllegalArgumentException("occurredAt must not precede the current state");
        }
        return value;
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static int requirePositive(int value, String fieldName) {
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

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public int getPartySize() {
        return partySize;
    }

    public WaitingSource getSource() {
        return source;
    }

    public long getQueueSequence() {
        return queueSequence;
    }

    public WaitingTeamStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCalledAt() {
        return calledAt;
    }

    public Instant getArrivalDeadline() {
        return arrivalDeadline;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getNoShowAt() {
        return noShowAt;
    }

    public Instant getClosedByStoreAt() {
        return closedByStoreAt;
    }
}
