package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

class RuntimeJsonAllowlistEnvironmentPostProcessorTest {

    @Test
    void registersThroughSpringFactoriesLoader() {
        List<String> factoryNames = SpringFactoriesLoader.loadFactoryNames(
                EnvironmentPostProcessor.class, getClass().getClassLoader());

        org.assertj.core.api.Assertions.assertThat(factoryNames)
                .contains(RuntimeJsonAllowlistEnvironmentPostProcessor.class.getName());
    }

    @Test
    void acceptsEmptyRuntimeJson() {
        assertThatCode(() -> RuntimeJsonAllowlistEnvironmentPostProcessor.validate("{}"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsApprovedS3Properties() {
        String runtimeJson = """
                {
                  "miriyum": {
                    "storage": {
                      "s3": {
                        "enabled": true,
                        "bucket": "approved-bucket",
                        "reconciliation": {
                          "batch-size": 50,
                          "claim-lease-seconds": 300
                        }
                      }
                    }
                  }
                }
                """;

        assertThatCode(() -> RuntimeJsonAllowlistEnvironmentPostProcessor.validate(runtimeJson))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsApprovedSseRuntimeProperties() {
        String runtimeJson = """
                {
                  "miriyum": {
                    "sse": {
                      "enabled": true,
                      "cursor-secret": "0123456789abcdef0123456789abcdef",
                      "timeout": "PT30S",
                      "heartbeat-interval": "PT5S",
                      "correction-interval": "PT2S",
                      "correction-batch-size": 100,
                      "max-connections-total": 200,
                      "max-connections-per-account": 6
                    }
                  }
                }
                """;

        assertThatCode(() -> RuntimeJsonAllowlistEnvironmentPostProcessor.validate(runtimeJson))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMalformedRuntimeJsonWithoutEchoingItsContents() {
        assertThatThrownBy(() -> RuntimeJsonAllowlistEnvironmentPostProcessor.validate("{not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SPRING_APPLICATION_JSON contains an unsupported runtime property");
    }

    @Test
    void rejectsUnapprovedPropertyWithoutEchoingItsValue() {
        String runtimeJson = """
                {"spring":{"datasource":{"password":"do-not-log-this"}}}
                """;

        assertThatThrownBy(() -> RuntimeJsonAllowlistEnvironmentPostProcessor.validate(runtimeJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SPRING_APPLICATION_JSON contains an unsupported runtime property");
    }

    @Test
    void rejectsUnapprovedEmptyObject() {
        assertThatThrownBy(() -> RuntimeJsonAllowlistEnvironmentPostProcessor.validate("{\"spring\":{}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SPRING_APPLICATION_JSON contains an unsupported runtime property");
    }

    @Test
    void rejectsForbiddenOriginalJsonWhenParsedJsonPropertySourceMasksIt() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("SPRING_APPLICATION_JSON", "{\"spring\":{\"datasource\":{\"password\":\"hidden\"}}}")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "spring.application.json", Map.of("spring.application.json", "{}")));

        assertThatThrownBy(() -> new RuntimeJsonAllowlistEnvironmentPostProcessor()
                .postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SPRING_APPLICATION_JSON contains an unsupported runtime property");
    }
}
