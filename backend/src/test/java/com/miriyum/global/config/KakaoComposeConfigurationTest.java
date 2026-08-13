package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoComposeConfigurationTest {

    @Test
    @DisplayName("카카오 state와 가입 티켓 비밀값을 빈 문자열로 컨테이너에 전달하지 않는다")
    void forwardsKakaoSecretsToContainers() throws IOException {
        assertComposeDefinesKakaoSecrets("../deploy/docker-compose.prod.yml");
        assertComposeDefinesKakaoSecrets("../deploy/local/docker-compose.dev.yml");
    }

    private void assertComposeDefinesKakaoSecrets(String composePath) throws IOException {
        String compose = Files.readString(Path.of(composePath));

        assertThat(compose)
                .contains("MIRIYUM_KAKAO_STATE_SECRET: ${MIRIYUM_KAKAO_STATE_SECRET:-}")
                .contains("MIRIYUM_KAKAO_SIGN_UP_TICKET_SECRET: ${MIRIYUM_KAKAO_SIGN_UP_TICKET_SECRET:-}");
    }
}
