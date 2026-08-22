package com.miriyum.domain.store.onboarding.service;

import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplicationVersion;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.AutomaticCheckStatus;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationVersionRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingAutomaticCheckJobRepository;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class StoreOnboardingFinalizationService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final StoreOnboardingApplicationRepository applications;
    private final StoreOnboardingApplicationVersionRepository versions;
    private final StoreOnboardingAutomaticCheckJobRepository jobs;
    private final StoreRepository stores;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public long finalizeApproved(long applicationId, long version, ApprovalMode mode) {
        StoreOnboardingApplication application = applications.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new IllegalStateException("onboarding application is missing"));
        if (application.getResultingStoreId() != null) return application.getResultingStoreId();
        requireApprovalMode(application, version, mode);
        jobs.findByStoreOnboardingApplicationIdAndApplicationVersion(applicationId, version)
                .filter(current -> current.getStatus() == AutomaticCheckStatus.PASSED)
                .orElseThrow(() -> new IllegalStateException("passed automatic check is required"));
        StoreOnboardingApplicationVersion snapshot = versions
                .findByStoreOnboardingApplicationIdAndApplicationVersion(applicationId, version)
                .orElseThrow(() -> new IllegalStateException("onboarding version is missing"));
        if (stores.existsByBusinessRegistrationNumber(snapshot.getBusinessRegistrationNumber())) {
            throw new ServiceException(StoreErrorCode.BUSINESS_NUMBER_CONFLICT);
        }
        try {
            Store saved = stores.saveAndFlush(Store.createVerified(
                    application.getStoreOperatorAccountId(), snapshot.getBusinessRegistrationNumber(),
                    snapshot.getName(), snapshot.getDescription(),
                    snapshot.getRegion(), snapshot.getAddress(), snapshot.getStoreCategoryCode(),
                    Set.of(objectMapper.readValue(snapshot.getTagCodesJson(), String[].class)),
                    snapshot.isReservationEnabled(), snapshot.isMenuHoldEnabled(),
                    snapshot.isPickupEnabled(), snapshot.getTimeZoneId(),
                    LocalDateTime.ofInstant(snapshot.getRequiredTermsAgreedAt(), BUSINESS_ZONE),
                    snapshot.getRequiredTermsVersion(), new VerifiedStoreGeocoding(
                            snapshot.getLatitude(), snapshot.getLongitude(), snapshot.getVerifiedAddress(),
                            snapshot.getGeocodingVerifiedAt())));
            application.completeApproval(version, saved.getId(), mode == ApprovalMode.AUTO, clock.instant());
            return saved.getId();
        } catch (DataIntegrityViolationException exception) {
            throw new ServiceException(StoreErrorCode.BUSINESS_NUMBER_CONFLICT);
        }
    }

    private static void requireApprovalMode(
            StoreOnboardingApplication application,
            long version,
            ApprovalMode mode
    ) {
        if (application.getCurrentVersion() != version || mode == null) {
            throw new IllegalStateException("stale finalization request");
        }
        boolean auto = mode == ApprovalMode.AUTO
                && !application.isReviewRequired()
                && application.getStatus() == ApplicationStatus.AUTO_CHECKING;
        boolean manual = mode == ApprovalMode.MANUAL
                && application.isReviewRequired()
                && application.getStatus() == ApplicationStatus.UNDER_REVIEW;
        if (!auto && !manual) throw new IllegalStateException("approval mode does not match application");
    }

    public enum ApprovalMode { AUTO, MANUAL }
}
