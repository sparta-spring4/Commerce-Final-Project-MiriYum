package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 매장과 KST 영업일별 중앙 FIFO 다음 순번을 직렬화한다. */
@Entity
@Table(name = "waiting_queue_sequences")
@IdClass(WaitingQueueSequence.Key.class)
public class WaitingQueueSequence {

    @Id
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Id
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "next_sequence", nullable = false)
    private long nextSequence;

    protected WaitingQueueSequence() {
    }

    private WaitingQueueSequence(long storeId, LocalDate businessDate) {
        if (storeId <= 0) {
            throw new IllegalArgumentException("storeId must be positive");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate must not be null");
        }
        this.storeId = storeId;
        this.businessDate = businessDate;
        this.nextSequence = 1L;
    }

    /** 아직 순번 행이 없는 매장 영업일의 첫 순번을 준비한다. */
    public static WaitingQueueSequence create(long storeId, LocalDate businessDate) {
        return new WaitingQueueSequence(storeId, businessDate);
    }

    /** 현재 순번을 반환하고 같은 잠금 행의 다음 순번을 한 번 증가시킨다. */
    public long allocate() {
        long allocated = nextSequence;
        nextSequence++;
        return allocated;
    }

    public Long getStoreId() {
        return storeId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public long getNextSequence() {
        return nextSequence;
    }

    /** JPA가 매장과 영업일 복합 PK를 식별하는 값 객체다. */
    public static class Key implements Serializable {

        private Long storeId;
        private LocalDate businessDate;

        public Key() {
        }

        public Key(Long storeId, LocalDate businessDate) {
            this.storeId = storeId;
            this.businessDate = businessDate;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(storeId, key.storeId)
                    && Objects.equals(businessDate, key.businessDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storeId, businessDate);
        }
    }
}
