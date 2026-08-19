package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuntimeJsonAllowlistEnvironmentPostProcessorTest {

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
}
