package com.miriyum.domain.payment.repository;

import com.miriyum.domain.payment.entity.PaymentAttempt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    long countByPayment_Id(Long paymentId);
    Optional<PaymentAttempt> findFirstByPayment_IdOrderByAttemptNoDesc(Long paymentId);
    Optional<PaymentAttempt> findByPrincipalIdAndIdempotencyKey(Long principalId, String idempotencyKey);
}
