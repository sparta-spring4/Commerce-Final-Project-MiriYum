package com.miriyum.domain.store.demo;

import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class MapDemoStoreSeeder {

    static final String DEMO_OPERATOR_EMAIL = "demo-map-operator@miriyum.local";
    private static final String REQUIRED_TERMS_VERSION = "STORE_ONBOARDING_REQUIRED_TERMS_V1";
    private static final LocalDateTime ONBOARDING_ACCEPTED_AT = LocalDateTime.of(2026, 8, 24, 9, 0);
    private static final Instant GEOCODING_VERIFIED_AT = Instant.parse("2026-08-24T00:00:00Z");

    private final StoreRepository storeRepository;
    private final StoreOperatorAccountRepository storeOperatorAccountRepository;

    MapDemoStoreSeeder(
            StoreRepository storeRepository,
            StoreOperatorAccountRepository storeOperatorAccountRepository
    ) {
        this.storeRepository = storeRepository;
        this.storeOperatorAccountRepository = storeOperatorAccountRepository;
    }

    void seed() {
        long operatorId = Objects.requireNonNull(
                findOrCreateDemoOperator().getId(),
                "demo operator id is required");
        demoStores().forEach(store -> {
            if (!storeRepository.existsByBusinessRegistrationNumber(store.businessRegistrationNumber())) {
                storeRepository.save(store.create(operatorId));
            }
        });
    }

    private StoreOperatorAccount findOrCreateDemoOperator() {
        return storeOperatorAccountRepository.findByEmail(DEMO_OPERATOR_EMAIL)
                .orElseGet(() -> storeOperatorAccountRepository.save(
                        StoreOperatorAccount.create(
                                DEMO_OPERATOR_EMAIL,
                                "DEMO_ACCOUNT_NOT_FOR_LOGIN",
                                "지도 시연 운영자")));
    }

    private List<DemoStore> demoStores() {
        return List.of(
                DemoStore.verified(
                        "9000000001", "시연 한강 한식당", Region.SEOUL,
                        "서울특별시 강남구 테헤란로 152", "37.500600", "127.036500"),
                DemoStore.verified(
                        "9000000002", "시연 해운대 식당", Region.BUSAN,
                        "부산광역시 해운대구 해운대해변로 264", "35.158700", "129.160400"),
                DemoStore.verified(
                        "9000000003", "시연 광주 식당", Region.GWANGJU,
                        "광주광역시 동구 서석로 15", "35.146200", "126.922600"),
                DemoStore.unverified(
                        "9000000004", "시연 좌표 미검증 매장", Region.SEOUL,
                        "서울특별시 마포구 양화로 160"));
    }

    private record DemoStore(
            String businessRegistrationNumber,
            String name,
            Region region,
            String address,
            BigDecimal latitude,
            BigDecimal longitude
    ) {

        private static DemoStore verified(
                String businessRegistrationNumber,
                String name,
                Region region,
                String address,
                String latitude,
                String longitude
        ) {
            return new DemoStore(
                    businessRegistrationNumber,
                    name,
                    region,
                    address,
                    new BigDecimal(latitude),
                    new BigDecimal(longitude));
        }

        private static DemoStore unverified(
                String businessRegistrationNumber,
                String name,
                Region region,
                String address
        ) {
            return new DemoStore(businessRegistrationNumber, name, region, address, null, null);
        }

        private Store create(long operatorId) {
            if (latitude == null) {
                return Store.create(
                        operatorId,
                        businessRegistrationNumber,
                        name,
                        "지도 시연용 로컬 매장입니다.",
                        region,
                        address,
                        "KOREAN",
                        Set.of(),
                        true,
                        false,
                        true,
                        "Asia/Seoul",
                        ONBOARDING_ACCEPTED_AT,
                        REQUIRED_TERMS_VERSION);
            }

            return Store.createVerified(
                    operatorId,
                    businessRegistrationNumber,
                    name,
                    "지도 시연용 로컬 매장입니다.",
                    region,
                    address,
                    "KOREAN",
                    Set.of(),
                    true,
                    false,
                    true,
                    "Asia/Seoul",
                    ONBOARDING_ACCEPTED_AT,
                    REQUIRED_TERMS_VERSION,
                    new VerifiedStoreGeocoding(latitude, longitude, address, GEOCODING_VERIFIED_AT));
        }
    }
}
