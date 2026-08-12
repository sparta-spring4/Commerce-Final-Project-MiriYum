package com.miriyum.domain.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/** 결제와 환불의 비밀값 없는 추가 전용 회계·감사 사건이다. */
@Entity
@Table(name = "payment_ledger_entries")
public class PaymentLedgerEntry {

    public enum Type {
        PAYMENT_PREPARED,
        PAYMENT_CONFIRMED,
        PAYMENT_FAILED,
        PAYMENT_RECONCILIATION_REQUIRED,
        REFUND_REQUESTED,
        REFUND_COMPLETED,
        REFUND_FAILED,
        REFUND_RECONCILIATION_REQUIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_ledger_entry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_pk", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_refund_pk")
    private PaymentRefund refund;

    @Column(name = "event_key", nullable = false, unique = true, length = 160)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private Type type;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PaymentLedgerEntry() {
    }

    private PaymentLedgerEntry(
            Payment payment,
            PaymentRefund refund,
            String eventKey,
            Type type,
            long amountMinor,
            Instant occurredAt
    ) {
        this.payment = Objects.requireNonNull(payment);
        this.refund = refund;
        this.eventKey = Objects.requireNonNull(eventKey);
        this.type = Objects.requireNonNull(type);
        if (amountMinor < 0) {
            throw new IllegalArgumentException("ledger amount must not be negative");
        }
        this.amountMinor = amountMinor;
        this.currency = payment.getCurrency();
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    public static PaymentLedgerEntry record(
            Payment payment,
            PaymentRefund refund,
            String eventKey,
            Type type,
            long amountMinor,
            Instant occurredAt
    ) {
        return new PaymentLedgerEntry(payment, refund, eventKey, type, amountMinor, occurredAt);
    }
}
