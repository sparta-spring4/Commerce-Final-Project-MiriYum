package com.miriyum.domain.store.service;

import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoreGeocodingService {

    private final StoreGeocodingPort geocodingPort;
    private final StoreGeocodingValidator validator;
    private final Clock clock;

    public VerifiedStoreGeocoding verify(Region region, String address) {
        return validator.validate(region, address, geocodingPort.geocode(address), clock.instant());
    }
}
