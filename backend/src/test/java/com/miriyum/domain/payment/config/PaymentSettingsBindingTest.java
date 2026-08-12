package com.miriyum.domain.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PaymentSettingsBindingTest {

    @Test
    @DisplayName("PortOne timeout 환경 설정을 PortOne 하위 설정에 바인딩한다")
    void bindsPortOneTimeoutEnvironmentProperties() {
        PaymentSettings settings = new PaymentSettings();
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "miriyum.payment.portone.connect-timeout", "2s",
                "miriyum.payment.portone.read-timeout", "5s"
        ));

        new Binder(source).bind("miriyum.payment", Bindable.ofInstance(settings));

        assertThat(settings.getPortone().getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.getPortone().getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
