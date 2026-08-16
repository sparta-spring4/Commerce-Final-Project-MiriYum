package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistoryQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistorySlice;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareWaitingReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.VerifiedWaitingReservationDeposit;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Payment의 유일한 공개 use-case Service이며 외부 호출을 DB transaction 밖에서 수행한다. */
@Service
public class PaymentService {

    private static final Set<String> RESERVATION_PREPARATION_CONSTRAINTS = Set.of(
            "uk_payments_source",
            "payments.uk_payments_source",
            "uk_payments_preparation_idempotency",
            "payments.uk_payments_preparation_idempotency"
    );

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
        boolean callerTransactionActive =
                TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return transactions.prepare(command, now());
        } catch (DataIntegrityViolationException race) {
            if (!isRetryableReservationPreparationConflict(race)) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
            if (callerTransactionActive) {
                throw new PaymentPreparationRetryableConflictException();
            }
            return transactions.replayPreparation(command);
        }
    }

    /** Prepares a Payment-owned deposit source for a waiting reservation without Reservation access. */
    public PaymentPreparation prepareWaitingReservationDeposit(
            PrepareWaitingReservationDepositCommand command
    ) {
        boolean callerTransactionActive =
                TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return transactions.prepareWaitingReservationDeposit(command, now());
        } catch (DataIntegrityViolationException race) {
            if (callerTransactionActive) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
            return transactions.replayWaitingReservationDeposit(command);
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

    public VerifiedWaitingReservationDeposit getVerifiedWaitingReservationDeposit(
            String paymentId,
            long waitingTeamId,
            long consumerAccountId
    ) {
        return transactions.getVerifiedWaitingReservationDeposit(
                paymentId, waitingTeamId, consumerAccountId);
    }

    public VerifiedWaitingReservationDeposit getCompletableWaitingReservationDeposit(
            String paymentId,
            long waitingTeamId,
            long consumerAccountId
    ) {
        return transactions.getCompletableWaitingReservationDeposit(
                paymentId, waitingTeamId, consumerAccountId);
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

    private static boolean isRetryableReservationPreparationConflict(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof ConstraintViolationException violation
                    && violation.getSQLException() != null
                    && violation.getSQLException().getErrorCode() == 1062) {
                String constraintName = violation.getConstraintName();
                if (constraintName != null
                        && RESERVATION_PREPARATION_CONSTRAINTS.contains(constraintName)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
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
