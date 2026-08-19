package com.miriyum.domain.store.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StoreGeocodingEnvironmentBindingTest {

    private static final String CANONICAL_KEY = "canonical-geocoding-key";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(StoreGeocodingConfig.class);

    @Test
    @DisplayName("canonical 지오코딩 키만 있으면 해당 값을 바인딩한다")
    void bindsCanonicalKeyWhenOnlyCanonicalKeyExists() {
        assertRestApiKey(CANONICAL_KEY,
                "MIRIYUM_STORE_GEOCODING_REST_API_KEY=" + CANONICAL_KEY);
    }

    @Test
    @DisplayName("전용 지오코딩 키가 없으면 빈 값으로 바인딩해 실패 폐쇄 경계를 유지한다")
    void bindsBlankWhenCanonicalKeyIsMissing() {
        assertRestApiKey("");
    }

    private void assertRestApiKey(String expected, String... propertyValues) {
        contextRunner.withPropertyValues(propertyValues).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StoreGeocodingProperties.class).restApiKey())
                    .isEqualTo(expected);
        });
    }
}
