package com.miriyum.domain.store.onboarding.service;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.service.StoreCommandFingerprint;
import com.miriyum.global.idempotency.RequestFingerprint;
import org.springframework.stereotype.Component;

@Component
public class StoreOnboardingRequestFingerprint {

    public String create(StoreCreateRequest request, String evidenceSha256) {
        if (evidenceSha256 == null || !evidenceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evidence checksum is required");
        }
        return RequestFingerprint.of(
                StoreCommandFingerprint.forCreate(request) + "|evidenceSha256=" + evidenceSha256);
    }
}
