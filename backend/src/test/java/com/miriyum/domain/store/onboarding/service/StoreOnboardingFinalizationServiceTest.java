package com.miriyum.domain.store.onboarding.service;

import static com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus.AUTO_APPROVED;
import static com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.Outcome.PASSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreModesRequest;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplicationVersion;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingAutomaticCheckJob;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationVersionRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingAutomaticCheckJobRepository;
import com.miriyum.domain.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StoreOnboardingFinalizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    @Mock StoreOnboardingApplicationRepository applications;
    @Mock StoreOnboardingApplicationVersionRepository versions;
    @Mock StoreOnboardingAutomaticCheckJobRepository jobs;
    @Mock StoreRepository stores;

    @Test
    void autoApprovalCreatesExactlyOneStoreAndRecordsItsId() {
        StoreOnboardingApplication application = StoreOnboardingApplication.reserve(
                11L, "key", "f".repeat(64), false, NOW);
        ReflectionTestUtils.setField(application, "id", 41L);
        application.beginEvidenceUpload(1L);
        application.attachVersion(1L, NOW);
        StoreOnboardingApplicationVersion version = StoreOnboardingApplicationVersion.snapshot(
                41L, 1L, null, "f".repeat(64), java.util.UUID.randomUUID().toString(),
                request(), geocoding(), false, "BUSINESS_REGISTRATION_AUTO_V1",
                "PLATFORM_REVIEW_V1", NOW, new ObjectMapper());
        assertThat(version.getBusinessType()).isEqualTo(BusinessType.OTHER);
        StoreOnboardingAutomaticCheckJob job = StoreOnboardingAutomaticCheckJob.pending(
                41L, 1L, NOW, NOW);
        long token = job.claim("worker", NOW, NOW.plusSeconds(30));
        job.record("worker", token, PASSED, "MATCHED", NOW, null);
        given(applications.findByIdForUpdate(41L)).willReturn(Optional.of(application));
        given(versions.findByStoreOnboardingApplicationIdAndApplicationVersion(41L, 1L))
                .willReturn(Optional.of(version));
        given(jobs.findByStoreOnboardingApplicationIdAndApplicationVersion(41L, 1L))
                .willReturn(Optional.of(job));
        given(stores.saveAndFlush(any(Store.class))).willAnswer(invocation -> {
            Store saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 77L);
            return saved;
        });
        StoreOnboardingFinalizationService service = new StoreOnboardingFinalizationService(
                applications, versions, jobs, stores, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.finalizeApproved(
                41L, 1L, StoreOnboardingFinalizationService.ApprovalMode.AUTO)).isEqualTo(77L);
        assertThat(application.getStatus()).isEqualTo(AUTO_APPROVED);
        assertThat(application.getResultingStoreId()).isEqualTo(77L);
        ArgumentCaptor<Store> savedStore = ArgumentCaptor.forClass(Store.class);
        org.mockito.BDDMockito.then(stores).should().saveAndFlush(savedStore.capture());
        assertThat(savedStore.getValue().getBusinessType()).isEqualTo(BusinessType.OTHER);
    }

    private static StoreCreateRequest request() {
        return new StoreCreateRequest(
                "1234567890", "미리윰", "", Region.SEOUL,
                "서울 중구 세종대로 110", "Asia/Seoul", "CAFE_BAKERY", List.of("DATE"),
                new StoreModesRequest(true, true, true), "미리윰 주식회사", "김대표",
                LocalDate.of(2020, 1, 1), "음식점업", "카페", true, true);
    }

    private static VerifiedStoreGeocoding geocoding() {
        return new VerifiedStoreGeocoding(
                new BigDecimal("37.566826"), new BigDecimal("126.9786567"),
                "서울 중구 세종대로 110", NOW);
    }
}
