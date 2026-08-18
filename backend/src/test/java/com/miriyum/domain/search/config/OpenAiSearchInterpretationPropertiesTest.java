package com.miriyum.domain.search.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OpenAiSearchInterpretationPropertiesTest {

    @Test
    void acceptsDisabledConfigurationWithoutApiKey() {
        var properties = properties(false, "", 8);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.apiKey()).isEmpty();
    }

    @Test
    void rejectsEnabledConfigurationWithoutApiKey() {
        assertThatThrownBy(() -> properties(true, " ", 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void rejectsMoreThanEightConcepts() {
        assertThatThrownBy(() -> properties(false, "", 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcepts");
    }

    private static OpenAiSearchInterpretationProperties properties(
            boolean enabled,
            String apiKey,
            int maxConcepts
    ) {
        return new OpenAiSearchInterpretationProperties(
                enabled,
                "https://api.openai.com",
                apiKey,
                "gpt-4o-mini",
                1_000,
                2_000,
                100,
                maxConcepts,
                200);
    }
}
