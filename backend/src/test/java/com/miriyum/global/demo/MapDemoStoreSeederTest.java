package com.miriyum.global.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.GeocodingStatus;
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
    void seedsThreeVerifiedStoresAndOneUnverifiedStore() {
        StoreRepository storeRepository = mock(StoreRepository.class);
        StoreOperatorAccountRepository operatorRepository = mock(StoreOperatorAccountRepository.class);
        StoreOperatorAccount operator = mock(StoreOperatorAccount.class);
        when(operator.getId()).thenReturn(31L);
        when(operatorRepository.findByEmail(MapDemoStoreSeeder.DEMO_OPERATOR_EMAIL))
                .thenReturn(Optional.of(operator));
        when(storeRepository.existsByBusinessRegistrationNumber(any())).thenReturn(false);

        new MapDemoStoreSeeder(storeRepository, operatorRepository).seed();

        ArgumentCaptor<Store> stores = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository, org.mockito.Mockito.times(4)).save(stores.capture());

        List<Store> createdStores = stores.getAllValues();
        assertThat(createdStores)
                .filteredOn(store -> store.getGeocodingStatus() == GeocodingStatus.VERIFIED)
                .hasSize(3)
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
}
