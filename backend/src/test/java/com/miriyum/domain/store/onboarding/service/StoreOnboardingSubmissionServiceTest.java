package com.miriyum.domain.store.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadService;
import com.miriyum.domain.store.evidence.ValidatedBusinessRegistrationEvidence;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReservedApplication;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.BusinessResult;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class StoreOnboardingSubmissionServiceTest {

    @Mock StoreOperatorAccountService accounts;
    @Mock StoreOnboardingRequestFingerprint fingerprints;
    @Mock StoreOnboardingTransactionExecutor transactions;
    @Mock BusinessRegistrationEvidenceUploadService uploads;
    @Mock StoreCreateRequest request;
    @Mock StoreOnboardingExternalOperations externalOperations;
    @Mock IdempotencyExecutor idempotency;

    private StoreOnboardingSubmissionService service;
    private final IdempotencyKey key = IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000");
    private final MockMultipartFile evidence =
            new MockMultipartFile("file", "license.pdf", "application/pdf", new byte[] {1});

    @BeforeEach
    void setUp() {
        service = new StoreOnboardingSubmissionService(
                accounts, fingerprints, transactions, uploads, externalOperations, idempotency);
        given(uploads.validate(evidence)).willReturn(new ValidatedBusinessRegistrationEvidence(
                "application/pdf", new byte[] {1}, "a".repeat(64)));
        given(fingerprints.create(request, "a".repeat(64))).willReturn("f".repeat(64));
        given(externalOperations.validateCatalogAndGeocode(request)).willReturn(org.mockito.Mockito.mock(
                VerifiedStoreGeocoding.class));
        lenient().when(idempotency.execute(any(), any())).thenAnswer(invocation -> {
            Supplier<BusinessResult<tools.jackson.databind.JsonNode>> work = invocation.getArgument(1);
            BusinessResult<tools.jackson.databind.JsonNode> result = work.get();
            return new IdempotentOutcome(
                    false, result.httpStatus(), result.responseCode(), result.resourceType(),
                    result.resourceId(), result.data());
        });
    }

    @Test
    void replayReturnsExistingApplicationWithoutUploadingAgain() {
        ReservedApplication reserved = new ReservedApplication(41L, 1L, true, true);
        IdempotentOutcome replay = new IdempotentOutcome(
                true, 202, "SUCCESS", "STORE_ONBOARDING_APPLICATION", "41", null);
        given(transactions.reserve(11L, key.value(), "f".repeat(64))).willReturn(reserved);
        given(transactions.replayOutcome(41L, 1L)).willReturn(replay);

        assertThat(service.submit(11L, key, request, evidence).data()).isEqualTo(replay.data());

        then(uploads).should(never()).storePending(anyLong(), anyLong(),
                any(ValidatedBusinessRegistrationEvidence.class));
    }

    @Test
    void changedEvidenceFingerprintKeepsCommon007Collision() {
        given(transactions.reserve(11L, key.value(), "f".repeat(64)))
                .willThrow(new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        assertThatThrownBy(() -> service.submit(11L, key, request, evidence))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(
                                CommonErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void supplementReplayUsesTheHistoricalApplicationVersion() {
        ReservedApplication reserved = new ReservedApplication(41L, 2L, true, true);
        IdempotentOutcome replay = new IdempotentOutcome(
                true, 202, "SUCCESS", "STORE_ONBOARDING_APPLICATION", "41", null);
        given(transactions.reserveSupplement(
                11L, 41L, key.value(), "f".repeat(64))).willReturn(reserved);
        given(transactions.replayOutcome(41L, 2L)).willReturn(replay);

        assertThat(service.supplement(11L, 41L, key, request, evidence).data())
                .isEqualTo(replay.data());

        then(uploads).should(never()).storePending(anyLong(), anyLong(),
                any(ValidatedBusinessRegistrationEvidence.class));
    }

    @Test
    void storedReplaySkipsMutableCatalogAndExternalGeocoding() {
        IdempotentOutcome replay = new IdempotentOutcome(
                true, 202, "SUCCESS", "STORE_ONBOARDING_APPLICATION", "41", null);
        doReturn(replay).when(idempotency).execute(any(), any());
        lenient().when(externalOperations.validateCatalogAndGeocode(request))
                .thenThrow(new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE));

        assertThat(service.submit(11L, key, request, evidence)).isSameAs(replay);

        then(externalOperations).should(never()).validateCatalogAndGeocode(any());
    }

    @Test
    void storedSupplementReplaySkipsMutableCatalogAndExternalGeocoding() {
        IdempotentOutcome replay = new IdempotentOutcome(
                true, 202, "SUCCESS", "STORE_ONBOARDING_APPLICATION", "41", null);
        doReturn(replay).when(idempotency).execute(any(), any());
        lenient().when(externalOperations.validateCatalogAndGeocode(request))
                .thenThrow(new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE));

        assertThat(service.supplement(11L, 41L, key, request, evidence)).isSameAs(replay);

        then(externalOperations).should(never()).validateCatalogAndGeocode(any());
    }

}
