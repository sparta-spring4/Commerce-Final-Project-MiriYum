package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts;
import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts.IntegrityStatus;
import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts.MeasurementStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession;
import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession.ResultCategory;
import com.miriyum.domain.reservation.waiting.repository.WaitingLocationProofSessionRepository;
import com.miriyum.domain.store.dto.contract.StoreWaitingLocationProfile;
import com.miriyum.domain.store.service.StoreService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WaitingLocationProofServiceTest {

    private static final long ACCOUNT_ID = 31L;
    private static final long STORE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-19T03:00:00Z");

    @Mock StoreService storeService;
    @Mock WaitingLocationProofSessionRepository repository;
    private WaitingLocationProofService service;
    private AtomicReference<WaitingLocationProofSession> saved;

    @BeforeEach
    void setUp() {
        saved = new AtomicReference<>();
        service = new WaitingLocationProofService(
                storeService, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exactRadiusIncludingAccuracyIsVerified() {
        assertThat(WaitingLocationPolicy.judgeDistance(
                new BigDecimal("2900"), new BigDecimal("100"), NOW, NOW,
                MeasurementStatus.MEASURED, IntegrityStatus.CLEAR))
                .isEqualTo(ResultCategory.VERIFIED);
    }

    @Test
    void distanceBeyondRadiusIncludingAccuracyIsRejected() {
        assertThat(WaitingLocationPolicy.judgeDistance(
                new BigDecimal("2900.001"), new BigDecimal("100"), NOW, NOW,
                MeasurementStatus.MEASURED, IntegrityStatus.CLEAR))
                .isEqualTo(ResultCategory.OUTSIDE_RADIUS);
    }

    @Test
    void insufficientAccuracyAndStaleMeasurementsNeverPass() {
        assertThat(WaitingLocationPolicy.judgeDistance(
                BigDecimal.ZERO, new BigDecimal("100.001"), NOW, NOW,
                MeasurementStatus.MEASURED, IntegrityStatus.CLEAR))
                .isEqualTo(ResultCategory.ACCURACY_INSUFFICIENT);
        assertThat(WaitingLocationPolicy.judgeDistance(
                BigDecimal.ZERO, BigDecimal.ONE, NOW.minusSeconds(31), NOW,
                MeasurementStatus.MEASURED, IntegrityStatus.CLEAR))
                .isEqualTo(ResultCategory.MEASUREMENT_STALE);
        assertThat(WaitingLocationPolicy.judgeDistance(
                BigDecimal.ZERO, BigDecimal.ONE, NOW.plusSeconds(6), NOW,
                MeasurementStatus.MEASURED, IntegrityStatus.CLEAR))
                .isEqualTo(ResultCategory.MEASUREMENT_STALE);
    }

    @Test
    void denialUnavailabilityAndSuspectedManipulationNeverPass() {
        assertThat(WaitingLocationPolicy.judgeDistance(
                null, null, null, NOW, MeasurementStatus.PERMISSION_DENIED,
                IntegrityStatus.CLEAR)).isEqualTo(ResultCategory.PERMISSION_DENIED);
        assertThat(WaitingLocationPolicy.judgeDistance(
                null, null, null, NOW, MeasurementStatus.POSITION_UNAVAILABLE,
                IntegrityStatus.CLEAR)).isEqualTo(ResultCategory.POSITION_UNAVAILABLE);
        assertThat(WaitingLocationPolicy.judgeDistance(
                BigDecimal.ZERO, BigDecimal.ONE, NOW, NOW, MeasurementStatus.MEASURED,
                IntegrityStatus.MANIPULATION_SUSPECTED))
                .isEqualTo(ResultCategory.MANIPULATION_SUSPECTED);
    }

    @Test
    void issuesMinimalProofWithoutRawLocationInPersistenceOrResponse() {
        captureSavedSession();
        given(storeService.getWaitingLocationProfile(STORE_ID)).willReturn(
                new StoreWaitingLocationProfile(
                        STORE_ID,
                        new BigDecimal("37.123456789012345"),
                        new BigDecimal("127.987654321098765"),
                        4L,
                        true));
        WaitingLocationProofContracts.Request request = new WaitingLocationProofContracts.Request(
                MeasurementStatus.MEASURED,
                new BigDecimal("37.123456789012345"),
                new BigDecimal("127.987654321098765"),
                new BigDecimal("73.25"),
                Instant.parse("2026-08-19T02:59:50Z"),
                IntegrityStatus.CLEAR);

        WaitingLocationProofContracts.Snapshot snapshot =
                service.issue(ACCOUNT_ID, STORE_ID, request);

        assertThat(snapshot.resultCategory()).isEqualTo(ResultCategory.VERIFIED);
        assertThat(snapshot.policyVersion()).isEqualTo("WAITING_LOCATION_V1");
        assertThat(snapshot.storeCoordinateVersion()).isEqualTo(4L);
        assertThat(snapshot.toString()).doesNotContain(
                "37.123456789012345", "127.987654321098765", "73.25", "02:59:50");
        assertThat(saved.get().toString()).doesNotContain(
                "37.123456789012345", "127.987654321098765", "73.25", "02:59:50");
        assertThat(saved.get().getExpiresAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void ineligibleStoreFailsClosed() {
        captureSavedSession();
        given(storeService.getWaitingLocationProfile(STORE_ID)).willReturn(
                new StoreWaitingLocationProfile(STORE_ID, null, null, 0L, false));

        WaitingLocationProofContracts.Snapshot snapshot = service.issue(
                ACCOUNT_ID, STORE_ID,
                new WaitingLocationProofContracts.Request(
                        MeasurementStatus.MEASURED,
                        new BigDecimal("37.0"), new BigDecimal("127.0"),
                        BigDecimal.ONE, NOW, IntegrityStatus.CLEAR));

        assertThat(snapshot.resultCategory()).isEqualTo(ResultCategory.POSITION_UNAVAILABLE);
    }

    private void captureSavedSession() {
        given(repository.save(any())).willAnswer(invocation -> {
            WaitingLocationProofSession value = invocation.getArgument(0);
            saved.set(value);
            return value;
        });
    }
}
