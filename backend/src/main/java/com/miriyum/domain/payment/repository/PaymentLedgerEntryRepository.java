package com.miriyum.domain.payment.repository;

import com.miriyum.domain.payment.entity.PaymentLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentLedgerEntryRepository extends JpaRepository<PaymentLedgerEntry, Long> {
}
