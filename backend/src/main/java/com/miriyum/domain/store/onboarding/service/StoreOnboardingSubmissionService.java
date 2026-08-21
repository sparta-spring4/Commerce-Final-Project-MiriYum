package com.miriyum.domain.store.onboarding.service;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadService;
import com.miriyum.domain.store.evidence.ValidatedBusinessRegistrationEvidence;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.domain.store.service.StoreCatalogPolicy;
import com.miriyum.domain.store.service.StoreGeocodingService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class StoreOnboardingSubmissionService {

    private final StoreOperatorAccountService operatorAccountService;
    private final StoreOnboardingRequestFingerprint fingerprints;
    private final StoreOnboardingTransactionExecutor transactions;
    private final BusinessRegistrationEvidenceUploadService evidenceUploads;
    private final StoreCatalogPolicy catalogPolicy;
    private final StoreGeocodingService geocodingService;

    public IdempotentOutcome submit(
            long operatorId,
            IdempotencyKey idempotencyKey,
            StoreCreateRequest request,
            MultipartFile evidence
    ) {
        operatorAccountService.requireActiveAccount(operatorId);
        ValidatedBusinessRegistrationEvidence validated = evidenceUploads.validate(evidence);
        catalogPolicy.validate(request.storeCategoryCode(), request.tagCodes());
        var geocoding = geocodingService.verify(request.region(), request.address());
        String fingerprint = fingerprints.create(request, validated.sha256());
        var reserved = transactions.reserve(operatorId, idempotencyKey.value(), fingerprint);
        if (reserved.replayed()) return transactions.replayOutcome(reserved.applicationId());
        transactions.beginEvidenceUpload(reserved);
        var pending = evidenceUploads.storePending(
                reserved.applicationId(), reserved.applicationVersion(), validated);
        return transactions.attachAndSubmit(
                operatorId, reserved, pending, fingerprint, request, null, geocoding);
    }

    public IdempotentOutcome supplement(
            long operatorId,
            long applicationId,
            IdempotencyKey idempotencyKey,
            StoreCreateRequest request,
            MultipartFile evidence
    ) {
        operatorAccountService.requireActiveAccount(operatorId);
        ValidatedBusinessRegistrationEvidence validated = evidenceUploads.validate(evidence);
        catalogPolicy.validate(request.storeCategoryCode(), request.tagCodes());
        var geocoding = geocodingService.verify(request.region(), request.address());
        String fingerprint = fingerprints.create(request, validated.sha256());
        var reserved = transactions.reserveSupplement(
                operatorId, applicationId, idempotencyKey.value(), fingerprint);
        if (reserved.replayed()) return transactions.replayOutcome(applicationId);
        var pending = evidenceUploads.storePending(
                applicationId, reserved.applicationVersion(), validated);
        return transactions.attachAndSubmit(
                operatorId, reserved, pending, fingerprint, request, idempotencyKey.value(), geocoding);
    }
}
