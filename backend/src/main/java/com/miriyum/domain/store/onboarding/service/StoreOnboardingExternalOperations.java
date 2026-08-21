package com.miriyum.domain.store.onboarding.service;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadService;
import com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadService.PendingEvidence;
import com.miriyum.domain.store.evidence.ValidatedBusinessRegistrationEvidence;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import com.miriyum.domain.store.service.StoreCatalogPolicy;
import com.miriyum.domain.store.service.StoreGeocodingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreOnboardingExternalOperations {

    private final StoreCatalogPolicy catalogPolicy;
    private final StoreGeocodingService geocodingService;
    private final BusinessRegistrationEvidenceUploadService evidenceUploads;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public VerifiedStoreGeocoding validateCatalogAndGeocode(StoreCreateRequest request) {
        catalogPolicy.validate(request.storeCategoryCode(), request.tagCodes());
        return geocodingService.verify(request.region(), request.address());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PendingEvidence storePending(
            long applicationId,
            long applicationVersion,
            ValidatedBusinessRegistrationEvidence validated
    ) {
        return evidenceUploads.storePending(applicationId, applicationVersion, validated);
    }
}
