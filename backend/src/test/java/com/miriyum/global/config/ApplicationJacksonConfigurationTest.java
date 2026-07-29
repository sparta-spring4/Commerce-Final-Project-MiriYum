package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
class ApplicationJacksonConfigurationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 알_수_없는_json_필드를_허용하지_않는다() {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .isTrue();
    }
}
