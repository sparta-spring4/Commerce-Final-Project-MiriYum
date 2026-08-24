package com.miriyum.global.demo;

import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "miriyum.runtime", name = "environment", havingValue = "staging")
class StagingMapDemoStoreConfiguration {

    @Bean
    MapDemoStoreSeeder stagingMapDemoStoreSeeder(
            StoreRepository storeRepository,
            StoreOperatorAccountRepository storeOperatorAccountRepository
    ) {
        return new MapDemoStoreSeeder(storeRepository, storeOperatorAccountRepository);
    }

    @Bean(name = "stagingMapDemoStoreSeederRunner")
    @ConditionalOnProperty(prefix = "miriyum.staging.map-demo-stores", name = "enabled", havingValue = "true")
    ApplicationRunner stagingMapDemoStoreSeederRunner(MapDemoStoreSeeder stagingMapDemoStoreSeeder) {
        return arguments -> stagingMapDemoStoreSeeder.seed();
    }
}
