package com.miriyum.domain.store.onboarding.service;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadService;
import com.miriyum.domain.store.evidence.ValidatedBusinessRegistrationEvidence;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.domain.store.service.StoreCatalogPolicy;
import com.miriyum.domain.store.service.StoreGeocodingService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class StoreOnboardingSubmissionService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String SUBMIT_COMMAND = "STORE_ONBOARDING_SUBMIT";
    private static final String SUPPLEMENT_COMMAND = "STORE_ONBOARDING_SUPPLEMENT";

    private final StoreOperatorAccountService operatorAccountService;
    private final StoreOnboardingRequestFingerprint fingerprints;
    private final StoreOnboardingTransactionExecutor transactions;
    private final BusinessRegistrationEvidenceUploadService evidenceUploads;
    private final StoreCatalogPolicy catalogPolicy;
    private final StoreGeocodingService geocodingService;
    private final IdempotencyExecutor idempotency;

    @Transactional
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
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE, operatorId, SUBMIT_COMMAND,
                idempotencyKey.value(), fingerprint);
        return idempotency.execute(command, () -> submitOnce(
                operatorId, idempotencyKey, request, validated, geocoding, fingerprint));
    }

    private BusinessResult<tools.jackson.databind.JsonNode> submitOnce(
            long operatorId,
            IdempotencyKey idempotencyKey,
            StoreCreateRequest request,
            ValidatedBusinessRegistrationEvidence validated,
            com.miriyum.domain.store.model.VerifiedStoreGeocoding geocoding,
            String fingerprint
    ) {
        var reserved = transactions.reserve(operatorId, idempotencyKey.value(), fingerprint);
        if (reserved.replayed()) return business(transactions.replayOutcome(
                reserved.applicationId(), reserved.applicationVersion()));
        transactions.beginEvidenceUpload(reserved);
        var pending = evidenceUploads.storePending(
                reserved.applicationId(), reserved.applicationVersion(), validated);
        return business(transactions.attachAndSubmit(
                operatorId, reserved, pending, fingerprint, request, null, geocoding));
    }

    @Transactional
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
        String commandFingerprint = RequestFingerprint.of(
                "applicationId=" + applicationId + "&payload=" + fingerprint);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE, operatorId, SUPPLEMENT_COMMAND,
                idempotencyKey.value(), commandFingerprint);
        return idempotency.execute(command, () -> supplementOnce(
                operatorId, applicationId, idempotencyKey, request,
                validated, geocoding, fingerprint));
    }

    private BusinessResult<tools.jackson.databind.JsonNode> supplementOnce(
            long operatorId,
            long applicationId,
            IdempotencyKey idempotencyKey,
            StoreCreateRequest request,
            ValidatedBusinessRegistrationEvidence validated,
            com.miriyum.domain.store.model.VerifiedStoreGeocoding geocoding,
            String fingerprint
    ) {
        var reserved = transactions.reserveSupplement(
                operatorId, applicationId, idempotencyKey.value(), fingerprint);
        if (reserved.replayed()) return business(transactions.replayOutcome(
                applicationId, reserved.applicationVersion()));
        var pending = evidenceUploads.storePending(
                applicationId, reserved.applicationVersion(), validated);
        return business(transactions.attachAndSubmit(
                operatorId, reserved, pending, fingerprint, request,
                idempotencyKey.value(), geocoding));
    }

    private static BusinessResult<tools.jackson.databind.JsonNode> business(
            IdempotentOutcome outcome) {
        return new BusinessResult<>(
                outcome.httpStatus(), outcome.responseCode(),
                outcome.resourceType(), outcome.resourceId(), outcome.data());
    }
}
