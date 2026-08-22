package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.request.ReservationSearchAvailabilityCondition;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-d")
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        }
)
class ReservationSearchAvailabilityServiceIT {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 17);
    private static final LocalTime START_TIME = LocalTime.of(18, 0);

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private ReservationSearchAvailabilityService service;

    @Autowired
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @MockitoBean
    private ReservationTimeResolutionService timeResolutionService;

    @BeforeEach
    void setUp() {
        capacityBucketRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        operatorRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("날짜 검색은 매장별 최신 정책만 사용하고 입력 순서와 중복을 보존한다")
    void usesLatestPolicyAndPreservesInputOrderAndDuplicates() {
        long availableStoreId = createStore(
                "search-available@example.com",
                "1234567890"
        );
        long unavailableStoreId = createStore(
                "search-unavailable@example.com",
                "1234567891"
        );
        capacityBucketRepository.saveAllAndFlush(List.of(
                bucket(availableStoreId, 1L, 10, 0),
                bucket(availableStoreId, 2L, 10, 0),
                bucket(unavailableStoreId, 1L, 10, 0),
                bucket(unavailableStoreId, 2L, 10, 10),
                ReservationCapacityBucket.create(
                        availableStoreId,
                        SERVICE_DATE.plusDays(1),
                        START_TIME,
                        LocalTime.of(19, 0),
                        10,
                        3,
                        0,
                        0,
                        1,
                        4,
                        true,
                        3L
                )
        ));
        given(timeResolutionService.resolveReservationTimes(anyList(), any()))
                .willAnswer(invocation -> {
                    List<Long> storeIds = invocation.getArgument(0);
                    ReservationTimeRequest request = invocation.getArgument(1);
                    return storeIds.stream()
                            .map(storeId -> resolved(storeId, request.startTime()))
                            .toList();
                });

        List<ReservationAvailabilityResult> results = service.getAvailabilities(
                List.of(unavailableStoreId, availableStoreId, unavailableStoreId),
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        null,
                        null,
                        2,
                        false
                )
        );

        assertThat(results).containsExactly(
                new ReservationAvailabilityResult(
                        unavailableStoreId,
                        ReservationAvailabilityStatus.UNAVAILABLE
                ),
                new ReservationAvailabilityResult(
                        availableStoreId,
                        ReservationAvailabilityStatus.AVAILABLE
                ),
                new ReservationAvailabilityResult(
                        unavailableStoreId,
                        ReservationAvailabilityStatus.UNAVAILABLE
                )
        );
    }

    private long createStore(String email, String registrationNumber) {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(email, "hashed", "owner")
        ).getId();
        return storeRepository.saveAndFlush(Store.create(
                operatorId,
                registrationNumber,
                "Search Availability Store",
                "",
                Region.SEOUL,
                "Seoul",
                "KOREAN",
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"
        )).getId();
    }

    private static ReservationCapacityBucket bucket(
            long storeId,
            long policyVersion,
            int maxPeople,
            int occupiedPeople
    ) {
        return ReservationCapacityBucket.create(
                storeId,
                SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 0),
                maxPeople,
                3,
                occupiedPeople,
                0,
                1,
                4,
                true,
                policyVersion
        );
    }

    private static ReservationTimeResolutionResult resolved(
            long storeId,
            LocalTime startTime
    ) {
        Instant startAt = SERVICE_DATE.atTime(startTime)
                .toInstant(ZoneOffset.ofHours(9));
        Instant endAt = startAt.plusSeconds(60 * 60);
        return ReservationTimeResolutionResult.resolved(
                storeId,
                new ResolvedReservationTime(
                        SERVICE_DATE,
                        startAt,
                        endAt,
                        endAt,
                        "Asia/Seoul",
                        32_400,
                        32_400,
                        32_400,
                        30,
                        60,
                        0,
                        storeId,
                        1L
                )
        );
    }
}
