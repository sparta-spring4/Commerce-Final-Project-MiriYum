package com.miriyum.domain.reservation.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Claims due deposit processes, then invokes process-first commands outside claim transactions. */
@Component
public class ReservationDepositProcessJob {

    private final ReservationDepositProcessService processService;
    private final ReservationDepositProcessCommandFacade commandFacade;
    private final String owner;
    private final int batchSize;

    @Autowired
    public ReservationDepositProcessJob(
            ReservationDepositProcessService processService,
            ReservationDepositProcessCommandFacade commandFacade,
            @Qualifier("reservationDepositProcessBatchSize") Integer batchSize
    ) {
        this(
                processService,
                commandFacade,
                "reservation-deposit-process-" + UUID.randomUUID(),
                requireBatchSize(batchSize));
    }

    ReservationDepositProcessJob(
            ReservationDepositProcessService processService,
            ReservationDepositProcessCommandFacade commandFacade
    ) {
        this(processService, commandFacade,
                "reservation-deposit-process-" + UUID.randomUUID(), 100);
    }

    ReservationDepositProcessJob(
            ReservationDepositProcessService processService,
            ReservationDepositProcessCommandFacade commandFacade,
            String owner,
            int batchSize
    ) {
        this.processService = processService;
        this.commandFacade = commandFacade;
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
        int reconciled = 0;
        for (ReservationDepositProcessService.Claim claim
                : processService.claimDue(owner, limit)) {
            try {
                if (commandFacade.reconcileClaimed(claim)) {
                    reconciled++;
                }
            } catch (RuntimeException failure) {
                // The unmodified lease expires and makes this claim safely reclaimable.
            }
        }
        return reconciled;
    }

    private static int requireBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        return batchSize;
    }
}
