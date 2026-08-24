package com.miriyum.global.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StagingMapDemoStoreConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StagingMapDemoStoreConfiguration.class)
            .withBean(StoreRepository.class, org.mockito.Mockito::mock)
            .withBean(StoreOperatorAccountRepository.class, org.mockito.Mockito::mock);

    @Test
    void registersTheSeederOnlyForExplicitStagingEnablement() {
        contextRunner
                .withPropertyValues(
                        "miriyum.runtime.environment=staging",
                        "miriyum.staging.map-demo-stores.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ApplicationRunner.class));
    }

    @Test
    void doesNotRegisterTheSeederForProductionEvenWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "miriyum.runtime.environment=production",
                        "miriyum.staging.map-demo-stores.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean("stagingMapDemoStoreSeederRunner"));
    }
}
