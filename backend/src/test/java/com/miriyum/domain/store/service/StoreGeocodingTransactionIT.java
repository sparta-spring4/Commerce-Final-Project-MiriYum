package com.miriyum.domain.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreModesRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreUpdateRequest;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.model.StoreGeocodingCandidate;
import com.miriyum.domain.store.model.StoreGeocodingResult;
import com.miriyum.domain.storeoperator.dto.auth.StoreOperatorSignUpRequest;
import com.miriyum.domain.storeoperator.service.StoreOperatorAuthService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.identity-verification.dev-stub-enabled=true",
            "miriyum.menu.schedule.enabled=false",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "spring.task.scheduling.enabled=false"
        })
class StoreGeocodingTransactionIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StoreService storeService;

    @Autowired
    private StoreOperatorAuthService storeOperatorAuthService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private StoreGeocodingPort geocodingPort;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
    }

    @Test
    @DisplayName("지오코딩은 transaction 밖에서 실행되고 검증 주소·좌표·버전은 함께 commit된다")
    void geocodesOutsideTransactionAndCommitsVerifiedLocationAtomically() {
        long operatorId = createOperator("geocoding-create@example.com");
        AtomicReference<Boolean> transactionActiveDuringGeocoding = new AtomicReference<>();
        given(geocodingPort.geocode("서울 중구 세종대로 110"))
                .willAnswer(invocation -> {
                    transactionActiveDuringGeocoding.set(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return geocodingResult(
                            "서울 중구 세종대로 110",
                            "서울",
                            "37.566826000000000",
                            "126.978656700000000");
                });

        StoreCommandResult result = storeService.create(
                operatorId,
                IdempotencyKey.parse("123e4567-e89b-12d3-a456-426614174101"),
                createRequest());

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(transactionActiveDuringGeocoding.get()).isFalse();
        StoreLocationRow row = locationRow(Long.parseLong(result.data().storeId()));
        assertThat(row.region()).isEqualTo("SEOUL");
        assertThat(row.address()).isEqualTo("서울 중구 세종대로 110");
        assertThat(row.addressVersion()).isEqualTo(1L);
        assertThat(row.geocodingStatus()).isEqualTo("VERIFIED");
        assertThat(row.latitude()).isEqualByComparingTo("37.566826000000000");
        assertThat(row.longitude()).isEqualByComparingTo("126.978656700000000");
        assertThat(row.geocodingAddressVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("주소 변경 검증 실패는 기존 주소·좌표·주소 버전을 모두 보존한다")
    void failedAddressUpdatePreservesCommittedLocation() {
        long operatorId = createOperator("geocoding-update@example.com");
        given(geocodingPort.geocode("서울 중구 세종대로 110"))
                .willReturn(geocodingResult(
                        "서울 중구 세종대로 110",
                        "서울",
                        "37.566826000000000",
                        "126.978656700000000"));
        StoreCommandResult created = storeService.create(
                operatorId,
                IdempotencyKey.parse("123e4567-e89b-12d3-a456-426614174102"),
                createRequest());
        long storeId = Long.parseLong(created.data().storeId());
        StoreLocationRow before = locationRow(storeId);
        given(geocodingPort.geocode("존재하지 않는 주소 99999"))
                .willReturn(new StoreGeocodingResult(
                        0,
                        List.of(),
                        "KAKAO_LOCAL",
                        "v2"));

        assertThatThrownBy(() -> storeService.update(
                operatorId,
                storeId,
                IdempotencyKey.parse("123e4567-e89b-12d3-a456-426614174103"),
                new StoreUpdateRequest(
                        null, null, null, "존재하지 않는 주소 99999",
                        null, null, null, null)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);

        assertThat(locationRow(storeId)).isEqualTo(before);
    }

    @Test
    @DisplayName("지오코딩 중 다른 위치 버전이 commit되면 잠금 후 stale 요청을 거부한다")
    void interveningLocationCommitRejectsStalePreflight() throws Exception {
        long operatorId = createOperator("geocoding-concurrency@example.com");
        given(geocodingPort.geocode("서울 중구 세종대로 110"))
                .willReturn(geocodingResult(
                        "서울 중구 세종대로 110",
                        "서울",
                        "37.566826000000000",
                        "126.978656700000000"));
        StoreCommandResult created = storeService.create(
                operatorId,
                IdempotencyKey.parse("123e4567-e89b-12d3-a456-426614174104"),
                createRequest());
        long storeId = Long.parseLong(created.data().storeId());

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch providerMayReturn = new CountDownLatch(1);
        given(geocodingPort.geocode("서울 중구 세종대로 120"))
                .willAnswer(invocation -> {
                    providerStarted.countDown();
                    if (!providerMayReturn.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("provider release was not signalled");
                    }
                    return geocodingResult(
                            "서울 중구 세종대로 120",
                            "서울",
                            "37.566900000000000",
                            "126.978700000000000");
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<StoreCommandResult> staleUpdate = executor.submit(() -> storeService.update(
                    operatorId,
                    storeId,
                    IdempotencyKey.parse("123e4567-e89b-12d3-a456-426614174105"),
                    new StoreUpdateRequest(
                            null, null, Region.SEOUL, "서울 중구 세종대로 120",
                            null, null, null, null)));

            assertThat(providerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            int updated = jdbcTemplate.update("""
                    UPDATE stores
                    SET address = ?,
                        address_version = address_version + 1,
                        geocoding_status = 'UNVERIFIED',
                        latitude = NULL,
                        longitude = NULL,
                        verified_address = NULL,
                        geocoding_verified_at = NULL,
                        geocoding_address_version = NULL
                    WHERE store_id = ?
                    """, "서울 중구 을지로 100", storeId);
            assertThat(updated).isEqualTo(1);
            providerMayReturn.countDown();

            assertThatThrownBy(() -> staleUpdate.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(error -> {
                        ServiceException cause = (ServiceException) error.getCause();
                        assertThat(cause.getErrorCode())
                                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                    });

            StoreLocationRow row = locationRow(storeId);
            assertThat(row.address()).isEqualTo("서울 중구 을지로 100");
            assertThat(row.addressVersion()).isEqualTo(2L);
            assertThat(row.geocodingStatus()).isEqualTo("UNVERIFIED");
            assertThat(row.latitude()).isNull();
            assertThat(row.longitude()).isNull();
        } finally {
            providerMayReturn.countDown();
            executor.shutdownNow();
        }
    }

    private long createOperator(String email) {
        return Long.parseLong(storeOperatorAuthService.signUp(
                new StoreOperatorSignUpRequest(
                        email,
                        "Password123!",
                        "Password123!",
                        "010-1000-0003",
                        "지오코딩 운영자")).accountId());
    }

    private StoreCreateRequest createRequest() {
        return new StoreCreateRequest(
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울 중구 세종대로 110",
                "Asia/Seoul",
                "CAFE_BAKERY",
                List.of("DATE"),
                new StoreModesRequest(true, true, true),
                true,
                true);
    }

    private StoreGeocodingResult geocodingResult(
            String address,
            String region1DepthName,
            String latitude,
            String longitude
    ) {
        return new StoreGeocodingResult(
                1,
                List.of(new StoreGeocodingCandidate(
                        address,
                        null,
                        region1DepthName,
                        longitude,
                        latitude)),
                "KAKAO_LOCAL",
                "v2");
    }

    private StoreLocationRow locationRow(long storeId) {
        return jdbcTemplate.queryForObject("""
                SELECT region,
                       address,
                       address_version,
                       geocoding_status,
                       latitude,
                       longitude,
                       geocoding_address_version
                FROM stores
                WHERE store_id = ?
                """, (resultSet, rowNumber) -> new StoreLocationRow(
                resultSet.getString("region"),
                resultSet.getString("address"),
                resultSet.getLong("address_version"),
                resultSet.getString("geocoding_status"),
                resultSet.getBigDecimal("latitude"),
                resultSet.getBigDecimal("longitude"),
                resultSet.getLong("geocoding_address_version")), storeId);
    }

    private record StoreLocationRow(
            String region,
            String address,
            long addressVersion,
            String geocodingStatus,
            java.math.BigDecimal latitude,
            java.math.BigDecimal longitude,
            long geocodingAddressVersion
    ) {
    }
}
