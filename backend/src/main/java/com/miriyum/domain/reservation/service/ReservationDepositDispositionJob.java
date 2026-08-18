package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentContracts.DispositionResult;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Calls Payment outside Reservation claim/result transactions. */
@Component
public class ReservationDepositDispositionJob {

    static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    static final Duration QUERY_DELAY = Duration.ofSeconds(30);
    static final int MAX_ATTEMPTS = 3;

    private final ReservationDepositDispositionService dispositionService;
    private final PaymentService paymentService;
    private final String owner;
    private final int batchSize;

    @Autowired
    public ReservationDepositDispositionJob(
            ReservationDepositDispositionService dispositionService,
            PaymentService paymentService,
            @Qualifier("reservationDepositDispositionBatchSize") Integer batchSize
    ) {
        this(
                dispositionService,
                paymentService,
                "deposit-disposition-" + UUID.randomUUID(),
                requireBatchSize(batchSize));
    }

    ReservationDepositDispositionJob(
            ReservationDepositDispositionService dispositionService,
            PaymentService paymentService
    ) {
        this(
                dispositionService,
                paymentService,
                "deposit-disposition-" + UUID.randomUUID(),
                100);
    }

    ReservationDepositDispositionJob(
            ReservationDepositDispositionService dispositionService,
            PaymentService paymentService,
            String owner,
            int batchSize
    ) {
        this.dispositionService = dispositionService;
        this.paymentService = paymentService;
        if (owner == null || owner.isBlank() || owner.length() > 64) {
            throw new IllegalArgumentException("owner must be 1 to 64 characters");
        }
        this.owner = owner;
        this.batchSize = requireBatchSize(batchSize);
    }

    public int runScheduled() {
        return runOnce(owner, batchSize);
    }

    public int runOnce(String owner, int limit) {
        int completed = 0;
        for (ReservationDepositDispositionService.Claim claim
                : dispositionService.claimDue(owner, limit)) {
            DispositionResult result;
            try {
                result = claim.operation()
                        == ReservationDepositDispositionObligation.Operation.QUERY
                        ? paymentService.getReservationDepositDisposition(claim.toQuery())
                        : paymentService.applyReservationDepositDisposition(
                                claim.toApplyCommand());
            } catch (ServiceException failure) {
                if (isRetryable(failure)) {
                    dispositionService.recordRetryableFailure(
                            claim, RETRY_DELAY, MAX_ATTEMPTS);
                } else {
                    dispositionService.recordRecoveryRequired(claim);
                }
                continue;
            } catch (RuntimeException failure) {
                dispositionService.recordRetryableFailure(
                        claim, RETRY_DELAY, MAX_ATTEMPTS);
                continue;
            }
            try {
                if (dispositionService.recordResult(
                        claim,
                        result,
                        RETRY_DELAY,
                        QUERY_DELAY,
                        MAX_ATTEMPTS)) {
                    completed++;
                }
            } catch (IllegalStateException failure) {
                dispositionService.recordRetryableFailure(
                        claim, RETRY_DELAY, MAX_ATTEMPTS);
            }
        }
        return completed;
    }

    private static boolean isRetryable(ServiceException failure) {
        return failure.getErrorCode() == CommonErrorCode.CONCURRENT_MODIFICATION
                || failure.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE;
    }

    private static int requireBatchSize(Integer value) {
        if (value == null || value < 1 || value > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        return value;
    }
}
