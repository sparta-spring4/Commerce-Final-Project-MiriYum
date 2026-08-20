package com.miriyum.domain.payment.repository;

import com.miriyum.domain.payment.entity.PaymentWebhookReceipt;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PaymentWebhookReceiptRepository
        extends JpaRepository<PaymentWebhookReceipt, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE payment_webhook_receipts
               SET outcome = 'PROCESSING', processed_at = :claimedAt
             WHERE webhook_message_id = :messageId
               AND (outcome = 'RECEIVED'
                    OR (outcome = 'PROCESSING' AND processed_at < :leaseBefore))
            """, nativeQuery = true)
    int claimProcessing(
            @Param("messageId") String messageId,
            @Param("claimedAt") Instant claimedAt,
            @Param("leaseBefore") Instant leaseBefore
    );
}
