package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ApplicationYamlTest {

    @Test
    void 공통_설정은_yaml로_제공한다() throws IOException {
        ClassPathResource yaml = new ClassPathResource("application.yml");

        assertThat(yaml.exists()).isTrue();
        assertThat(new ClassPathResource("application.properties").exists()).isFalse();

        PropertySource<?> properties =
                new YamlPropertySourceLoader().load("application", yaml).getFirst();

        assertThat(properties.getProperty("spring.application.name")).isEqualTo("miriyum-backend");
        assertThat(properties.getProperty("spring.datasource.url")).isEqualTo("${MIRIYUM_DB_URL}");
        assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${MIRIYUM_DB_USERNAME}");
        assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${MIRIYUM_DB_PASSWORD}");
        assertThat(properties.getProperty("spring.jackson.deserialization.fail-on-unknown-properties"))
                .isEqualTo(true);
        assertThat(properties.getProperty("server.forward-headers-strategy"))
                .isEqualTo("native");
        assertThat(properties.getProperty("server.tomcat.remoteip.remote-ip-header"))
                .isEqualTo("x-real-ip");
        assertThat(properties.getProperty("server.tomcat.remoteip.protocol-header"))
                .isEqualTo("x-forwarded-proto");
        assertThat(properties.getProperty("server.tomcat.remoteip.internal-proxies"))
                .isEqualTo("172\\.29\\.81\\.2");
        assertThat(properties.getProperty("miriyum.rate-limit.public-store-read.max-requests"))
                .isEqualTo("${MIRIYUM_RATE_LIMIT_PUBLIC_STORE_READ_MAX_REQUESTS:60}");
        assertThat(properties.getProperty("miriyum.rate-limit.public-store-read.window-seconds"))
                .isEqualTo("${MIRIYUM_RATE_LIMIT_PUBLIC_STORE_READ_WINDOW_SECONDS:60}");
    }
}
