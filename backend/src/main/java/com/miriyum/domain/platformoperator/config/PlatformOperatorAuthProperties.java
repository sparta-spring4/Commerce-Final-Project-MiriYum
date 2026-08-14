package com.miriyum.domain.platformoperator.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "miriyum.platform-operator")
public class PlatformOperatorAuthProperties {
    private boolean enabled;
    private final TemporaryPassword temporaryPassword = new TemporaryPassword();

    @PostConstruct
    void validateEnabledConfiguration() {
        if (!enabled) return;
        if (temporaryPassword.validity == null || temporaryPassword.validity.isZero()
                || temporaryPassword.validity.isNegative() || temporaryPassword.maxFailures == null
                || temporaryPassword.maxFailures <= 0) {
            throw new IllegalStateException(
                    "platform operator temporary-password validity and max-failures are required and positive");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public TemporaryPassword getTemporaryPassword() { return temporaryPassword; }

    public static class TemporaryPassword {
        private Duration validity;
        private Integer maxFailures;
        public Duration getValidity() { return validity; }
        public void setValidity(Duration validity) { this.validity = validity; }
        public Integer getMaxFailures() { return maxFailures; }
        public void setMaxFailures(Integer maxFailures) { this.maxFailures = maxFailures; }
    }
}
