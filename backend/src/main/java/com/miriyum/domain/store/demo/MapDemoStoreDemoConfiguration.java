package com.miriyum.domain.store.demo;

import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
class MapDemoStoreDemoConfiguration {

    @Bean
    MapDemoStoreSeeder mapDemoStoreSeeder(
            StoreRepository storeRepository,
            StoreOperatorAccountRepository storeOperatorAccountRepository
    ) {
        return new MapDemoStoreSeeder(storeRepository, storeOperatorAccountRepository);
    }

    @Bean(name = "mapDemoStoreSeederRunner")
    @ConditionalOnProperty(prefix = "miriyum.demo.map-stores", name = "enabled", havingValue = "true")
    ApplicationRunner mapDemoStoreSeederRunner(MapDemoStoreSeeder mapDemoStoreSeeder) {
        return arguments -> mapDemoStoreSeeder.seed();
    }
}
