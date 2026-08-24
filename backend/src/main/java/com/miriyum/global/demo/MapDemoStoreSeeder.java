package com.miriyum.global.demo;

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

    static final String DEMO_OPERATOR_EMAIL = "local-map-seed-operator@miriyum.local";
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
                                "로컬 지도 운영자")));
    }

    private List<DemoStore> demoStores() {
        return List.of(
                DemoStore.verified(
                        "9000000001", "마루 한식당", Region.SEOUL,
                        "서울특별시 강남구 테헤란로 152", "37.500600", "127.036500"),
                DemoStore.verified(
                        "9000000002", "해운대 바다식당", Region.BUSAN,
                        "부산광역시 해운대구 해운대해변로 264", "35.158700", "129.160400"),
                DemoStore.verified(
                        "9000000003", "무등 한상", Region.GWANGJU,
                        "광주광역시 동구 서석로 15", "35.146200", "126.922600"),
                DemoStore.unverified(
                        "9000000004", "새봄 식당", Region.SEOUL,
                        "서울특별시 마포구 양화로 160"),
                DemoStore.verified(
                        "9000000005", "동성로 한상", Region.DAEGU,
                        "대구광역시 중구 동성로 2", "35.869400", "128.594000"),
                DemoStore.verified(
                        "9000000006", "중앙로 식탁", Region.DAEJEON,
                        "대전광역시 중구 중앙로 101", "36.328700", "127.428000"));
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
                        "정성스러운 한식을 제공하는 지역 식당입니다.",
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
                    "정성스러운 한식을 제공하는 지역 식당입니다.",
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
