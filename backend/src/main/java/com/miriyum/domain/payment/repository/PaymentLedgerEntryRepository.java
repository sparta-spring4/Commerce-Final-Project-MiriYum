package com.miriyum.domain.payment.repository;

import com.miriyum.domain.payment.entity.PaymentLedgerEntry;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentLedgerEntryRepository extends JpaRepository<PaymentLedgerEntry, Long> {

    @Query("""
            select entry.payment.paymentId as paymentId,
                   entry.type as type,
                   entry.amountMinor as amountMinor,
                   entry.occurredAt as occurredAt
              from PaymentLedgerEntry entry
             where entry.payment.paymentId in :paymentIds
             order by entry.payment.paymentId, entry.occurredAt, entry.id
            """)
    List<MonitoringEvent> findMonitoringEvents(@Param("paymentIds") List<String> paymentIds);

    interface MonitoringEvent {
        String getPaymentId();
        PaymentLedgerEntry.Type getType();
        long getAmountMinor();
        Instant getOccurredAt();
    }
}
