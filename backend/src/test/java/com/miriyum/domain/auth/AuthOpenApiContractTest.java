package com.miriyum.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthOpenApiContractTest {

    private static final Path AUTH_OPEN_API = Path.of(
            "..", "docs", "specs", "auth-account", "openapi.yaml");

    @Test
    @DisplayName("로그아웃은 Refresh 쿠키가 없어도 멱등적으로 종료할 수 있다")
    void logoutDoesNotRequireRefreshCookie() throws IOException {
        String openApi = Files.readString(AUTH_OPEN_API).replace("\r\n", "\n");

        assertThat(operation(openApi, "/api/v1/consumers/auth/sessions/current:"))
                .doesNotContain("security:\n        - consumerRefreshCookie: []");
        assertThat(operation(openApi, "/api/v1/store-operators/auth/sessions/current:"))
                .doesNotContain("security:\n        - storeOperatorRefreshCookie: []");
    }

    @Test
    @DisplayName("일반 사용자 로그아웃은 선택적 Access 교차 확인과 서버 실패 응답을 공개한다")
    void consumerLogoutPublishesQrRevocationCredentialOutcomes() throws IOException {
        String openApi = Files.readString(AUTH_OPEN_API).replace("\r\n", "\n");
        String logout = operation(openApi, "/api/v1/consumers/auth/sessions/current:");

        assertThat(logout)
                .contains("security:\n        - {}\n        - bearerAuth: []")
                .contains("\"401\":\n          $ref: \"#/components/responses/AccessRefreshSubjectMismatch\"")
                .contains("\"503\":\n          $ref: \"#/components/responses/ConsumerLogoutServiceUnavailable\"");
        assertThat(openApi)
                .doesNotContain("OptionalAuthorization:")
                .contains("AccessRefreshSubjectMismatch:")
                .contains("code: AUTH_016");
    }

    @Test
    @DisplayName("일반 로그인과 카카오 로그인은 같은 15분 Access Token 수명을 안내한다")
    void tokenResponsesUseTheSameAccessTokenExpiry() throws IOException {
        String openApi = Files.readString(AUTH_OPEN_API).replace("\r\n", "\n");

        assertThat(schema(openApi, "KakaoLoginData:"))
                .contains("expiresIn:\n          type: integer\n          const: 900");
        assertThat(schema(openApi, "TokenData:"))
                .contains("expiresIn:\n          type: integer\n          const: 900");
    }

    private String operation(String openApi, String path) {
        int start = openApi.indexOf("  " + path);
        assertThat(start)
                .as("OpenAPI path %s must exist", path)
                .isGreaterThanOrEqualTo(0);
        int end = openApi.indexOf("\n  /", start + 1);
        return openApi.substring(start, end == -1 ? openApi.length() : end);
    }

    private String schema(String openApi, String schemaName) {
        int start = openApi.indexOf("    " + schemaName);
        assertThat(start)
                .as("OpenAPI schema %s must exist", schemaName)
                .isGreaterThanOrEqualTo(0);
        Matcher nextSchema = Pattern.compile("(?m)^    [A-Z][A-Za-z0-9]+:")
                .matcher(openApi);
        int end = nextSchema.find(start + 1) ? nextSchema.start() : -1;
        return openApi.substring(start, end == -1 ? openApi.length() : end);
    }
}
