package com.miriyum.domain.store.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadService;
import com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadService.PendingEvidence;
import com.miriyum.domain.store.evidence.ValidatedBusinessRegistrationEvidence;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReservedApplication;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingRequestFingerprint;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingSubmissionService;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingTransactionExecutor;
import com.miriyum.domain.store.service.StoreCatalogPolicy;
import com.miriyum.domain.store.service.StoreGeocodingService;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(properties = {
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false"
})
class StoreOnboardingSubmissionIdempotencyIT {

    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440001");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @Autowired StoreOnboardingSubmissionService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean StoreOperatorAccountService accounts;
    @MockitoBean StoreOnboardingRequestFingerprint fingerprints;
    @MockitoBean StoreOnboardingTransactionExecutor transactions;
    @MockitoBean BusinessRegistrationEvidenceUploadService uploads;
    @MockitoBean StoreCatalogPolicy catalog;
    @MockitoBean StoreGeocodingService geocoding;

    private StoreCreateRequest request;
    private MockMultipartFile evidence;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE idempotency_commands");
        request = org.mockito.Mockito.mock(StoreCreateRequest.class);
        evidence = new MockMultipartFile(
                "file", "license.pdf", "application/pdf", new byte[] {1, 2, 3});
        var validated = new ValidatedBusinessRegistrationEvidence(
                "application/pdf", new byte[] {1, 2, 3}, "a".repeat(64));
        given(uploads.validate(evidence)).willReturn(validated);
        given(fingerprints.create(request, validated.sha256())).willReturn("f".repeat(64));
        given(geocoding.verify(any(), any())).willReturn(
                org.mockito.Mockito.mock(VerifiedStoreGeocoding.class));
        given(uploads.storePending(anyLong(), anyLong(), eq(validated)))
                .willReturn(new PendingEvidence(
                        UUID.fromString("550e8400-e29b-41d4-a716-446655440099"),
                        validated.sha256(), validated.contentType(), 3L));
        given(transactions.attachAndSubmit(
                anyLong(), any(), any(), any(), any(), any(), any()))
                .willReturn(new IdempotentOutcome(
                        false, 202, "SUCCESS", "STORE_ONBOARDING_APPLICATION", "41",
                        JsonMapper.builder().build().createObjectNode()
                                .put("applicationId", "41")
                                .put("applicationVersion", 1L)));
    }

    @Test
    void concurrentInitialSubmissionRunsUploadCallbackOnce() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(transactions.reserve(11L, KEY.value(), "f".repeat(64)))
                .willAnswer(invocation -> {
                    callbackStarted.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return new ReservedApplication(41L, 1L, false, false);
                });

        Set<Boolean> replayed = runConcurrently(
                () -> service.submit(11L, KEY, request, evidence), callbackStarted, release);

        assertThat(replayed).containsExactlyInAnyOrder(false, true);
        verify(transactions, times(1)).reserve(11L, KEY.value(), "f".repeat(64));
        verify(uploads, times(1)).storePending(
                anyLong(), anyLong(), any(ValidatedBusinessRegistrationEvidence.class));
    }

    @Test
    void concurrentSupplementRunsUploadCallbackOnce() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(transactions.reserveSupplement(11L, 41L, KEY.value(), "f".repeat(64)))
                .willAnswer(invocation -> {
                    callbackStarted.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return new ReservedApplication(41L, 2L, true, false);
                });

        Set<Boolean> replayed = runConcurrently(
                () -> service.supplement(11L, 41L, KEY, request, evidence),
                callbackStarted, release);

        assertThat(replayed).containsExactlyInAnyOrder(false, true);
        verify(transactions, times(1)).reserveSupplement(
                11L, 41L, KEY.value(), "f".repeat(64));
        verify(uploads, times(1)).storePending(
                anyLong(), anyLong(), any(ValidatedBusinessRegistrationEvidence.class));
    }

    @Test
    void firstSubmissionInvokesGeocodingAndStorageOutsideTransaction() {
        AtomicBoolean geocodingOutsideTransaction = new AtomicBoolean();
        AtomicBoolean storageOutsideTransaction = new AtomicBoolean();
        given(geocoding.verify(any(), any())).willAnswer(invocation -> {
            geocodingOutsideTransaction.set(
                    !TransactionSynchronizationManager.isActualTransactionActive());
            return org.mockito.Mockito.mock(VerifiedStoreGeocoding.class);
        });
        given(transactions.reserve(11L, KEY.value(), "f".repeat(64)))
                .willReturn(new ReservedApplication(41L, 1L, false, false));
        given(uploads.storePending(anyLong(), anyLong(), any(ValidatedBusinessRegistrationEvidence.class)))
                .willAnswer(invocation -> {
                    storageOutsideTransaction.set(
                            !TransactionSynchronizationManager.isActualTransactionActive());
                    return new PendingEvidence(
                            UUID.fromString("550e8400-e29b-41d4-a716-446655440099"),
                            "a".repeat(64), "application/pdf", 3L);
                });

        service.submit(11L, KEY, request, evidence);

        assertThat(geocodingOutsideTransaction).isTrue();
        assertThat(storageOutsideTransaction).isTrue();
    }

    @Test
    void storedReplaySurvivesGeocodingProviderOutage() {
        given(transactions.reserve(11L, KEY.value(), "f".repeat(64)))
                .willReturn(new ReservedApplication(41L, 1L, false, false));

        IdempotentOutcome first = service.submit(11L, KEY, request, evidence);
        given(geocoding.verify(any(), any())).willThrow(new IllegalStateException("provider down"));

        IdempotentOutcome replay = service.submit(11L, KEY, request, evidence);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.data()).isEqualTo(first.data());
        verify(geocoding, times(1)).verify(any(), any());
        verify(uploads, times(1)).storePending(
                anyLong(), anyLong(), any(ValidatedBusinessRegistrationEvidence.class));
    }

    private static Set<Boolean> runConcurrently(
            Supplier<IdempotentOutcome> call,
            CountDownLatch callbackStarted,
            CountDownLatch release
    ) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(call::get);
            assertThat(callbackStarted.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(call::get);
            release.countDown();
            return Set.of(
                    first.get(20, TimeUnit.SECONDS).replayed(),
                    second.get(20, TimeUnit.SECONDS).replayed());
        }
    }
}
