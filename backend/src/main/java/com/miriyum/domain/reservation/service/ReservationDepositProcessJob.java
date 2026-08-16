package com.miriyum.domain.reservation.service;

import org.springframework.stereotype.Component;

/** Claims due deposit processes, then invokes process-first commands outside claim transactions. */
@Component
public class ReservationDepositProcessJob {

    private final ReservationDepositProcessService processService;
    private final ReservationDepositProcessCommandFacade commandFacade;

    public ReservationDepositProcessJob(
            ReservationDepositProcessService processService,
            ReservationDepositProcessCommandFacade commandFacade
    ) {
        this.processService = processService;
        this.commandFacade = commandFacade;
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
}
