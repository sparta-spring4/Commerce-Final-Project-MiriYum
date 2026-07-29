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
    }
}
