package com.miriyum.global.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.GeocodingStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MapDemoStoreSeederTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MapDemoStoreDemoConfiguration.class)
            .withBean(StoreRepository.class, () -> mock(StoreRepository.class))
            .withBean(StoreOperatorAccountRepository.class, () -> mock(StoreOperatorAccountRepository.class))
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"));

    @Test
    void demoProfileOnlyDoesNotRegisterTheSeederRunner() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean("mapDemoStoreSeederRunner"));
    }

    @Test
    void explicitEnableRegistersTheSeederRunner() {
        contextRunner
                .withPropertyValues("miriyum.demo.map-stores.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ApplicationRunner.class));
    }

    @Test
    void seedsEverySupportedRegionWithCoordinatesAndOneUnverifiedStore() {
        StoreRepository storeRepository = mock(StoreRepository.class);
        StoreOperatorAccountRepository operatorRepository = mock(StoreOperatorAccountRepository.class);
        StoreOperatorAccount operator = mock(StoreOperatorAccount.class);
        when(operator.getId()).thenReturn(31L);
        when(operatorRepository.findByEmail(MapDemoStoreSeeder.DEMO_OPERATOR_EMAIL))
                .thenReturn(Optional.of(operator));
        when(storeRepository.existsByBusinessRegistrationNumber(any())).thenReturn(false);

        new MapDemoStoreSeeder(storeRepository, operatorRepository).seed();

        ArgumentCaptor<Store> stores = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository, org.mockito.Mockito.times(6)).save(stores.capture());

        List<Store> createdStores = stores.getAllValues();
        assertThat(createdStores)
                .filteredOn(store -> store.getGeocodingStatus() == GeocodingStatus.VERIFIED)
                .hasSize(Region.values().length)
                .allSatisfy(store -> {
                    assertThat(store.getLatitude()).isNotNull();
                    assertThat(store.getLongitude()).isNotNull();
                    assertThat(store.getVerifiedAddress()).isNotBlank();
                    assertThat(store.getGeocodingVerifiedAt()).isNotNull();
                    assertThat(store.getGeocodingAddressVersion()).isEqualTo(store.getAddressVersion());
                });
        assertThat(createdStores)
                .filteredOn(store -> store.getGeocodingStatus() == GeocodingStatus.UNVERIFIED)
                .singleElement()
                .satisfies(store -> {
                    assertThat(store.getLatitude()).isNull();
                    assertThat(store.getLongitude()).isNull();
                });
    }

    @Test
    void seedsMapStoresForEverySupportedRegionAtConfiguredDemoAddresses() {
        StoreRepository storeRepository = mock(StoreRepository.class);
        StoreOperatorAccountRepository operatorRepository = mock(StoreOperatorAccountRepository.class);
        StoreOperatorAccount operator = mock(StoreOperatorAccount.class);
        when(operator.getId()).thenReturn(31L);
        when(operatorRepository.findByEmail(MapDemoStoreSeeder.DEMO_OPERATOR_EMAIL))
                .thenReturn(Optional.of(operator));
        when(storeRepository.existsByBusinessRegistrationNumber(any())).thenReturn(false);

        new MapDemoStoreSeeder(storeRepository, operatorRepository).seed();

        ArgumentCaptor<Store> stores = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository, org.mockito.Mockito.times(6)).save(stores.capture());

        assertThat(stores.getAllValues())
                .filteredOn(store -> store.getGeocodingStatus() == GeocodingStatus.VERIFIED)
                .extracting(Store::getRegion, Store::getAddress)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(Region.SEOUL, "서울특별시 강남구 테헤란로 152"),
                        org.assertj.core.groups.Tuple.tuple(Region.BUSAN, "부산광역시 해운대구 해운대해변로 264"),
                        org.assertj.core.groups.Tuple.tuple(Region.DAEGU, "대구광역시 중구 동성로 2"),
                        org.assertj.core.groups.Tuple.tuple(Region.DAEJEON, "대전광역시 중구 중앙로 101"),
                        org.assertj.core.groups.Tuple.tuple(Region.GWANGJU, "광주광역시 동구 서석로 15"));
    }

    @Test
    void preservesPreviouslySeededBusinessRegistrationNumberIdentities() {
        StoreRepository storeRepository = mock(StoreRepository.class);
        StoreOperatorAccountRepository operatorRepository = mock(StoreOperatorAccountRepository.class);
        StoreOperatorAccount operator = mock(StoreOperatorAccount.class);
        when(operator.getId()).thenReturn(31L);
        when(operatorRepository.findByEmail(MapDemoStoreSeeder.DEMO_OPERATOR_EMAIL))
                .thenReturn(Optional.of(operator));
        when(storeRepository.existsByBusinessRegistrationNumber(any())).thenReturn(false);

        new MapDemoStoreSeeder(storeRepository, operatorRepository).seed();

        ArgumentCaptor<Store> stores = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository, org.mockito.Mockito.times(6)).save(stores.capture());

        assertThat(stores.getAllValues())
                .extracting(Store::getBusinessRegistrationNumber, Store::getName, Store::getRegion)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("9000000001", "마루 한식당", Region.SEOUL),
                        org.assertj.core.groups.Tuple.tuple("9000000002", "해운대 바다식당", Region.BUSAN),
                        org.assertj.core.groups.Tuple.tuple("9000000003", "무등 한상", Region.GWANGJU),
                        org.assertj.core.groups.Tuple.tuple("9000000004", "새봄 식당", Region.SEOUL),
                        org.assertj.core.groups.Tuple.tuple("9000000005", "동성로 한상", Region.DAEGU),
                        org.assertj.core.groups.Tuple.tuple("9000000006", "중앙로 식탁", Region.DAEJEON));
    }
}
