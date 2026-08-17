package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.StoreReservationImpact;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreReservationImpactQueryService {

    private final ReservationRepository reservations;

    public StoreReservationImpactQueryService(ReservationRepository reservations) {
        this.reservations = reservations;
    }

    @Transactional(readOnly = true)
    public StoreReservationImpact inspect(long storeId, Instant now) {
        if (storeId <= 0 || now == null) {
            throw new IllegalArgumentException("storeId and now are required");
        }
        List<Long> ids = reservations.findConfirmedFutureIdsByStoreId(storeId, now);
        return new StoreReservationImpact(storeId, ids.size(), Set.copyOf(ids));
    }
}
