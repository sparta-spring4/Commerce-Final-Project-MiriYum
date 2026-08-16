package com.miriyum.domain.auth.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 요청 제한 필터가 실제 HTTP 응답 수준에서, 그리고 실제 MySQL(Testcontainers) 위에서
 * 동작하는지 확인한다. H2는 {@link RateLimitWindowRepository}의 원자적 upsert 문법
 * (MySQL 전용 {@code ON DUPLICATE KEY UPDATE})을 지원하지 않아 증거로 쓰지 않는다
 * ({@code docs/service-policies/18-scale-reliability.md} SCALE-014).
 */
@Tag("integration")
@Tag("integration-shard-d")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.rate-limit.sign-up.max-requests=5",
            "miriyum.rate-limit.sign-up.window-seconds=600",
            "miriyum.rate-limit.login.max-requests=5",
            "miriyum.rate-limit.login.window-seconds=600",
            "miriyum.rate-limit.token-refresh.max-requests=30",
            "miriyum.rate-limit.token-refresh.window-seconds=60",
            "miriyum.rate-limit.csrf-preparation.max-requests=2",
            "miriyum.rate-limit.csrf-preparation.window-seconds=60",
            "miriyum.rate-limit.staging-bypass.runtime-environment=staging",
            "miriyum.rate-limit.staging-bypass.source-ip=8.8.8.8"
        })
@AutoConfigureMockMvc
class RateLimitFilterTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("플랫폼 운영자 공개 인증 중 로그인·재발급·CSRF 준비만 요청 제한한다")
    void limitsOnlyApprovedPlatformOperatorPublicAuthRoutes() {
        RateLimitFilter filter = new RateLimitFilter(null, null, new StagingRateLimitBypass("", ""));

        assertThatFilterApplies(filter, "POST", "/api/v1/platform-operators/auth/sessions");
        assertThatFilterApplies(filter, "POST", "/api/v1/platform-operators/auth/token-refreshes");
        assertThatFilterApplies(filter, "GET", "/api/v1/platform-operators/auth/csrf-tokens/current");
        assertThatFilterDoesNotApply(filter, "POST", "/api/v1/platform-operators/auth/accounts");
    }

    @Test
    @DisplayName("한도를 초과한 요청은 429와 Retry-After를 반환한다")
    void blocksRequestsOverTheLimit() throws Exception {
        // given: CSRF 준비 한도(2회)만큼 먼저 소비
        RequestPostProcessor ip = withRemoteAddr("10.0.0.1");
        mockMvc.perform(get("/api/v1/consumers/auth/csrf-tokens/current").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consumers/auth/csrf-tokens/current").with(ip)).andExpect(status().isOk());

        // when & then
        mockMvc.perform(get("/api/v1/consumers/auth/csrf-tokens/current").with(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMON_010"))
                .andExpect(header().exists("Retry-After"));
    }

    @ParameterizedTest
    @MethodSource("stagingBypassRoutes")
    @DisplayName("staging 허용 IP는 로그인과 토큰 갱신 한도를 넘어도 429를 받지 않는다")
    void bypassesLoginAndRefreshLimitsForConfiguredStagingIp(String path, int maxRequests) throws Exception {
        RequestPostProcessor ip = withRemoteAddr("8.8.8.8");

        for (int attempt = 0; attempt <= maxRequests; attempt++) {
            mockMvc.perform(post(path).with(ip))
                    .andExpect(result -> {
                        if (result.getResponse().getStatus() == 429) {
                            throw new AssertionError("staging 허용 IP가 " + path + "에서 429를 받았습니다.");
                        }
                    });
        }
    }

    @Test
    @DisplayName("제한 대상이 아닌 경로(로그아웃)는 요청 제한을 받지 않는다")
    void doesNotLimitUnlistedEndpoints() throws Exception {
        // 로그아웃은 목록에 없으므로 몇 번을 호출해도 429가 아니라(401/403 등) 다른 결과여야 한다.
        RequestPostProcessor ip = withRemoteAddr("10.0.0.3");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(delete("/api/v1/consumers/auth/sessions/current").with(ip))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        if (status == 429) {
                            throw new AssertionError("로그아웃은 요청 제한 대상이 아니어야 하는데 429가 반환됐습니다.");
                        }
                    });
        }
    }

    @Test
    @DisplayName("서로 다른 등급(회원가입/CSRF 준비)은 독립적으로 카운트한다")
    void tracksDifferentCategoriesIndependently() throws Exception {
        // given: CSRF 준비 한도(2회)를 다 소비해도
        RequestPostProcessor ip = withRemoteAddr("10.0.0.2");
        mockMvc.perform(get("/api/v1/consumers/auth/csrf-tokens/current").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consumers/auth/csrf-tokens/current").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consumers/auth/csrf-tokens/current").with(ip))
                .andExpect(status().isTooManyRequests());

        // when & then: 다른 등급(토큰 재발급)은 영향받지 않는다
        mockMvc.perform(post("/api/v1/consumers/auth/token-refreshes").with(ip))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 429) {
                        throw new AssertionError("토큰 재발급은 CSRF 준비와 별개 등급이라 429를 받으면 안 됩니다.");
                    }
                });
    }

    @ParameterizedTest(name = "{2} {1}은 {0} 등급 한도({3})에서 정확히 허용/거부를 나눈다")
    @MethodSource("limitedRoutes")
    @DisplayName("14개 제한 대상 경로 각각이 기대한 등급의 한도로 정확히 매핑된다")
    void eachLimitedRouteEnforcesItsOwnCategoryLimit(
            String categoryName, String path, String method, int maxRequests, String uniqueSuffix
    ) throws Exception {
        // given: 이 경로만 쓰는 전용 IP로 한도만큼 요청
        RequestPostProcessor ip = withRemoteAddr("10.1." + uniqueSuffix + ".1");
        for (int i = 0; i < maxRequests; i++) {
            int attempt = i + 1;
            mockMvc.perform(request(HttpMethod.valueOf(method), path).with(ip))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        if (status == 429) {
                            throw new AssertionError(
                                    method + " " + path + "은 한도(" + maxRequests + ") 이내인 "
                                            + attempt + "번째 요청에서 429를 받으면 안 됩니다.");
                        }
                    });
        }

        // when & then: 한도를 넘는 다음 요청은 429여야 한다
        mockMvc.perform(request(HttpMethod.valueOf(method), path).with(ip))
                .andExpect(status().isTooManyRequests());
    }

    private static Stream<Arguments> limitedRoutes() {
        return Stream.of(
                Arguments.of("SIGN_UP", "/api/v1/consumers/auth/accounts", "POST", 5, "1"),
                Arguments.of("LOGIN", "/api/v1/consumers/auth/sessions", "POST", 5, "2"),
                Arguments.of("LOGIN", "/api/v1/consumers/auth/kakao/authorizations", "POST", 5, "3"),
                Arguments.of("LOGIN", "/api/v1/consumers/auth/kakao/sessions", "POST", 5, "4"),
                Arguments.of("SIGN_UP", "/api/v1/consumers/auth/kakao/accounts", "POST", 5, "5"),
                Arguments.of("TOKEN_REFRESH", "/api/v1/consumers/auth/token-refreshes", "POST", 30, "6"),
                Arguments.of("CSRF_PREPARATION", "/api/v1/consumers/auth/csrf-tokens/current", "GET", 2, "7"),
                Arguments.of("SIGN_UP", "/api/v1/store-operators/auth/accounts", "POST", 5, "8"),
                Arguments.of("LOGIN", "/api/v1/store-operators/auth/sessions", "POST", 5, "9"),
                Arguments.of("LOGIN", "/api/v1/store-operators/auth/kakao/authorizations", "POST", 5, "10"),
                Arguments.of("LOGIN", "/api/v1/store-operators/auth/kakao/sessions", "POST", 5, "11"),
                Arguments.of("SIGN_UP", "/api/v1/store-operators/auth/kakao/accounts", "POST", 5, "12"),
                Arguments.of("TOKEN_REFRESH", "/api/v1/store-operators/auth/token-refreshes", "POST", 30, "13"),
                Arguments.of("CSRF_PREPARATION", "/api/v1/store-operators/auth/csrf-tokens/current", "GET", 2, "14")
        );
    }

    private static Stream<Arguments> stagingBypassRoutes() {
        return Stream.of(
                Arguments.of("/api/v1/consumers/auth/sessions", 5),
                Arguments.of("/api/v1/consumers/auth/token-refreshes", 30)
        );
    }

    @Test
    @DisplayName("같은 IP의 일반 사용자 가입과 매장 운영자 가입은 같은 등급 한도를 합쳐서 나눠 쓴다")
    void consumerAndStoreOperatorSignUpShareTheSameSignUpLimit() throws Exception {
        // given: 회원가입 한도(5회)를 Consumer 3회 + StoreOperator 2회로 나눠 소비
        RequestPostProcessor ip = withRemoteAddr("10.2.0.1");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/consumers/auth/accounts").with(ip))
                    .andExpect(result -> {
                        if (result.getResponse().getStatus() == 429) {
                            throw new AssertionError("한도(5) 이내인데 Consumer 가입이 429를 받았습니다.");
                        }
                    });
        }
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/store-operators/auth/accounts").with(ip))
                    .andExpect(result -> {
                        if (result.getResponse().getStatus() == 429) {
                            throw new AssertionError("한도(5) 이내인데 StoreOperator 가입이 429를 받았습니다.");
                        }
                    });
        }

        // when & then: 두 namespace를 합쳐 6번째 요청이므로 어느 쪽이든 429여야 한다
        mockMvc.perform(post("/api/v1/consumers/auth/accounts").with(ip))
                .andExpect(status().isTooManyRequests());
    }

    private static RequestPostProcessor withRemoteAddr(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }

    private static void assertThatFilterApplies(RateLimitFilter filter, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        org.assertj.core.api.Assertions.assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    private static void assertThatFilterDoesNotApply(RateLimitFilter filter, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        org.assertj.core.api.Assertions.assertThat(filter.shouldNotFilter(request)).isTrue();
    }
}
