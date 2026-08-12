package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistoryQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistorySlice;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

/** Payment의 유일한 공개 use-case Service이며 외부 호출을 DB transaction 밖에서 수행한다. */
@Service
public class PaymentService {

    private final PaymentTransactionService transactions;
    private final PaymentProviderClient providerClient;
    private final Clock clock;

    public PaymentService(
            PaymentTransactionService transactions,
            PaymentProviderClient providerClient,
            Clock clock
    ) {
        this.transactions = transactions;
        this.providerClient = providerClient;
        this.clock = clock;
    }

    public PaymentPreparation prepareReservationDeposit(PrepareReservationDepositCommand command) {
        try {
            return transactions.prepare(command, now());
        } catch (DataIntegrityViolationException race) {
            return transactions.replayPreparation(command);
        }
    }

    public PaymentResult confirmPayment(ConfirmPaymentCommand command) {
        Instant now = now();
        PaymentTransactionService.ConfirmationClaim claim =
                transactions.claimConfirmation(command, now);
        return confirmClaim(claim);
    }

    PaymentResult confirmWebhook(
            String portOnePaymentId,
            String webhookMessageId,
            String webhookType,
            String webhookCancellationId
    ) {
        PaymentTransactionService.ConfirmationClaim claim =
                transactions.claimWebhookConfirmation(
                        portOnePaymentId,
                        webhookMessageId,
                        webhookType,
                        webhookCancellationId,
                        now()
                );
        return confirmClaim(claim);
    }

    private PaymentResult confirmClaim(PaymentTransactionService.ConfirmationClaim claim) {
        if (!claim.requiresProviderLookup()) {
            return claim.completedResult();
        }
        try {
            ProviderPayment providerPayment = providerClient.getPayment(claim.portOnePaymentId());
            if (!matchesProviderMapping(claim, providerPayment)) {
                transactions.markConfirmationMismatch(claim, now());
                throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
            }
            return transactions.finalizeConfirmation(claim, providerPayment, now());
        } catch (PaymentProviderClient.ProviderUnavailableException exception) {
            return transactions.markConfirmationUnknown(claim, now());
        }
    }

    public RefundResult requestRefund(RequestRefundCommand command) {
        PaymentTransactionService.RefundClaim claim =
                transactions.claimRefund(command, now());
        if (claim.rejectionError() != null) {
            throw new ServiceException(claim.rejectionError());
        }
        if (!claim.requiresProviderCall()) {
            return claim.completedResult();
        }
        try {
            ProviderCancellation cancellation = providerClient.cancelPayment(
                    claim.portOnePaymentId(),
                    claim.refundId(),
                    claim.amountMinor(),
                    claim.currency(),
                    claim.reasonCode()
            );
            if (cancellation.amountMinor() != claim.amountMinor()
                    || !cancellation.currency().equals(claim.currency())) {
                transactions.markRefundMismatch(claim, now());
                throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
            }
            return transactions.finalizeRefund(claim, cancellation, now());
        } catch (PaymentProviderClient.ProviderUnavailableException exception) {
            return transactions.markRefundUnknown(claim, now());
        }
    }

    public PaymentResult getOwnedPayment(String paymentId, String consumerAccountId) {
        return transactions.getOwnedPayment(paymentId, parsePositiveId(consumerAccountId));
    }

    public PaymentHistorySlice getConsumerPaymentHistory(PaymentHistoryQuery query) {
        return transactions.getConsumerPaymentHistory(query);
    }

    private static long parsePositiveId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("consumerAccountId must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("consumerAccountId must be a positive number", exception);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean matchesProviderMapping(
            PaymentTransactionService.ConfirmationClaim claim,
            ProviderPayment providerPayment
    ) {
        return claim.portOnePaymentId().equals(providerPayment.portOnePaymentId())
                && claim.amountMinor() == providerPayment.amountMinor()
                && claim.currency().equals(providerPayment.currency());
    }
}
