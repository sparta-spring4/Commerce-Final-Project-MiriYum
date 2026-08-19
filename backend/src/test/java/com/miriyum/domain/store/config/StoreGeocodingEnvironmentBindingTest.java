package com.miriyum.domain.store.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StoreGeocodingEnvironmentBindingTest {

    private static final String CANONICAL_KEY = "canonical-geocoding-key";
    private static final String KAKAO_REST_KEY = "kakao-rest-key";
    private static final String LEGACY_KEY = "legacy-geocoding-key";

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
    @DisplayName("legacy 지오코딩 키만 있으면 전환용 fallback 값을 바인딩한다")
    void fallsBackToLegacyKeyWhenCanonicalKeyIsMissing() {
        assertRestApiKey(LEGACY_KEY,
                "MIRIYUM_KAKAO_LOCAL_REST_API_KEY=" + LEGACY_KEY);
    }

    @Test
    @DisplayName("카카오 OAuth REST 키만 있으면 전환용 fallback 값을 바인딩한다")
    void fallsBackToKakaoRestKeyWhenCanonicalKeyIsMissing() {
        assertRestApiKey(KAKAO_REST_KEY,
                "MIRIYUM_KAKAO_REST_API_KEY=" + KAKAO_REST_KEY);
    }

    @Test
    @DisplayName("canonical과 카카오 OAuth REST 키가 모두 있으면 canonical 값을 우선한다")
    void prefersCanonicalKeyOverKakaoRestKey() {
        assertRestApiKey(CANONICAL_KEY,
                "MIRIYUM_STORE_GEOCODING_REST_API_KEY=" + CANONICAL_KEY,
                "MIRIYUM_KAKAO_REST_API_KEY=" + KAKAO_REST_KEY,
                "MIRIYUM_KAKAO_LOCAL_REST_API_KEY=" + LEGACY_KEY);
    }

    @Test
    @DisplayName("카카오 OAuth REST 키와 legacy 키가 모두 있으면 카카오 OAuth REST 키를 우선한다")
    void prefersKakaoRestKeyOverLegacyKey() {
        assertRestApiKey(KAKAO_REST_KEY,
                "MIRIYUM_KAKAO_REST_API_KEY=" + KAKAO_REST_KEY,
                "MIRIYUM_KAKAO_LOCAL_REST_API_KEY=" + LEGACY_KEY);
    }

    @Test
    @DisplayName("지오코딩 키가 모두 없으면 빈 값으로 바인딩해 실패 폐쇄 경계를 유지한다")
    void bindsBlankWhenBothKeysAreMissing() {
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
