package com.miriyum.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthOpenApiContractTest {

    private static final Path AUTH_OPEN_API = Path.of(
            "..", "docs", "specs", "auth-account", "openapi.yaml");

    @Test
    @DisplayName("로그아웃은 Refresh 쿠키가 없어도 멱등적으로 종료할 수 있다")
    void logoutDoesNotRequireRefreshCookie() throws IOException {
        String openApi = Files.readString(AUTH_OPEN_API).replace("\r\n", "\n");

        assertThat(operation(openApi, "/api/v1/consumer-auth/sessions/current:"))
                .doesNotContain("security:\n        - consumerRefreshCookie: []");
        assertThat(operation(openApi, "/api/v1/store-operator-auth/sessions/current:"))
                .doesNotContain("security:\n        - storeOperatorRefreshCookie: []");
    }

    private String operation(String openApi, String path) {
        int start = openApi.indexOf("  " + path);
        int end = openApi.indexOf("\n  /", start + 1);
        return openApi.substring(start, end == -1 ? openApi.length() : end);
    }
}
