package com.miriyum.domain.auth.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 요청 제한 필터가 실제 HTTP 응답 수준에서 동작하는지 확인한다.
 */
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:rate-limit-filter-test;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.rate-limit.max-requests=2",
            "miriyum.rate-limit.window-seconds=60"
        })
@AutoConfigureMockMvc
class RateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("한도를 초과한 요청은 429와 Retry-After를 반환한다")
    void blocksRequestsOverTheLimit() throws Exception {
        // given: 한도(2회)만큼 먼저 소비
        mockMvc.perform(get("/api/v1/consumer-auth/csrf-tokens/current")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consumer-auth/csrf-tokens/current")).andExpect(status().isOk());

        // when & then
        mockMvc.perform(get("/api/v1/consumer-auth/csrf-tokens/current"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMON_010"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("제한 대상이 아닌 경로(로그아웃)는 요청 제한을 받지 않는다")
    void doesNotLimitUnlistedEndpoints() throws Exception {
        // 로그아웃은 목록에 없으므로 몇 번을 호출해도 429가 아니라(401/403 등) 다른 결과여야 한다.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(delete("/api/v1/consumer-auth/sessions/current"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        if (status == 429) {
                            throw new AssertionError("로그아웃은 요청 제한 대상이 아니어야 하는데 429가 반환됐습니다.");
                        }
                    });
        }
    }
}
