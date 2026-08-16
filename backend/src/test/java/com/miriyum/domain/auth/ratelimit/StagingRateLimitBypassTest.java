package com.miriyum.domain.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StagingRateLimitBypassTest {

    @ParameterizedTest
    @MethodSource("loginAndRefreshCategories")
    @DisplayName("staging의 단일 공인 IP는 로그인과 토큰 갱신 요청만 예외로 허용한다")
    void allowsLoginAndRefreshOnlyForConfiguredStagingPublicIp(RateLimitCategory category) {
        StagingRateLimitBypass bypass = new StagingRateLimitBypass("staging", "8.8.8.8");

        assertThat(bypass.allows(category, "8.8.8.8")).isTrue();
    }

    @Test
    @DisplayName("설정 IP와 다른 staging 요청에는 예외를 적용하지 않는다")
    void rejectsDifferentStagingIp() {
        StagingRateLimitBypass bypass = new StagingRateLimitBypass("staging", "8.8.8.8");

        assertThat(bypass.allows(RateLimitCategory.LOGIN, "1.1.1.1")).isFalse();
    }

    @ParameterizedTest
    @MethodSource("nonAuthenticationMeasurementCategories")
    @DisplayName("설정 IP라도 가입과 CSRF 준비 요청에는 예외를 적용하지 않는다")
    void rejectsCategoriesOutsideLoginAndRefresh(RateLimitCategory category) {
        StagingRateLimitBypass bypass = new StagingRateLimitBypass("staging", "8.8.8.8");

        assertThat(bypass.allows(category, "8.8.8.8")).isFalse();
    }

    @ParameterizedTest
    @MethodSource("inactiveEnvironments")
    @DisplayName("staging 이외 환경에서는 IP가 설정돼도 예외를 적용하지 않는다")
    void rejectsConfiguredIpOutsideStaging(String runtimeEnvironment) {
        StagingRateLimitBypass bypass = new StagingRateLimitBypass(runtimeEnvironment, "8.8.8.8");

        assertThat(bypass.allows(RateLimitCategory.LOGIN, "8.8.8.8")).isFalse();
    }

    @Test
    @DisplayName("staging에서 IP가 비어 있으면 예외를 적용하지 않는다")
    void rejectsBlankStagingIp() {
        StagingRateLimitBypass bypass = new StagingRateLimitBypass("staging", " ");

        assertThat(bypass.allows(RateLimitCategory.LOGIN, "8.8.8.8")).isFalse();
    }

    @ParameterizedTest
    @MethodSource("nonPublicOrNonSingleIpValues")
    @DisplayName("staging에서는 단일 공인 IP가 아닌 설정을 거부한다")
    void rejectsNonPublicOrNonSingleStagingIp(String sourceIp) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StagingRateLimitBypass("staging", sourceIp));
    }

    private static Stream<RateLimitCategory> loginAndRefreshCategories() {
        return Stream.of(RateLimitCategory.LOGIN, RateLimitCategory.TOKEN_REFRESH);
    }

    private static Stream<RateLimitCategory> nonAuthenticationMeasurementCategories() {
        return Stream.of(RateLimitCategory.SIGN_UP, RateLimitCategory.CSRF_PREPARATION);
    }

    private static Stream<String> inactiveEnvironments() {
        return Stream.of("", "local", "production", "STAGING");
    }

    private static Stream<Arguments> nonPublicOrNonSingleIpValues() {
        return Stream.of(
                Arguments.of("10.0.0.1"),
                Arguments.of("127.0.0.1"),
                Arguments.of("169.254.10.20"),
                Arguments.of("192.0.2.10"),
                Arguments.of("224.0.0.1"),
                Arguments.of("8.8.8.8/32"),
                Arguments.of("8.8.8.8,1.1.1.1"),
                Arguments.of("load-test.example.com"),
                Arguments.of("not-an-ip")
        );
    }
}
