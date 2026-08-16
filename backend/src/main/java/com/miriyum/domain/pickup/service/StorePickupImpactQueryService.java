package com.miriyum.domain.pickup.service;

import com.miriyum.domain.pickup.dto.StorePickupImpact;
import com.miriyum.domain.pickup.entity.PickupStatus;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorePickupImpactQueryService {

    private final PickupReservationRepository pickups;

    public StorePickupImpactQueryService(PickupReservationRepository pickups) {
        this.pickups = pickups;
    }

    @Transactional(readOnly = true)
    public StorePickupImpact inspect(long storeId, Instant now) {
        if (storeId <= 0 || now == null) {
            throw new IllegalArgumentException("storeId and now are required");
        }
        var ids = pickups.findIdsByStoreIdAndStatusAndPickupAtGreaterThanEqual(
                storeId, PickupStatus.CONFIRMED, now);
        return new StorePickupImpact(storeId, ids.size(), Set.copyOf(ids));
    }
}
