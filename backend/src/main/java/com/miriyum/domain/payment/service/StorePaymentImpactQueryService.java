package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.dto.StorePaymentImpact;
import com.miriyum.domain.payment.repository.PaymentRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorePaymentImpactQueryService {

    private static final String RESERVATION_DEPOSIT = "RESERVATION_DEPOSIT";

    private final PaymentRepository payments;

    public StorePaymentImpactQueryService(PaymentRepository payments) {
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public StorePaymentImpact inspectReservationDeposits(Set<Long> reservationIds) {
        if (reservationIds == null) {
            throw new IllegalArgumentException("reservationIds are required");
        }
        if (reservationIds.isEmpty()) {
            return new StorePaymentImpact(0, 0);
        }
        List<String> references = reservationIds.stream()
                .sorted()
                .map(String::valueOf)
                .toList();
        return new StorePaymentImpact(
                references.size(),
                payments.countUnsettledBySourceReferences(RESERVATION_DEPOSIT, references));
    }
}
