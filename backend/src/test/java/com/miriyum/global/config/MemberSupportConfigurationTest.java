package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportProperties;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MemberSupportConfigurationTest {

    @Test
    void enabledConfigurationRequiresDistinctDigestSecretAndAes256Key() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> new MemberSupportProperties(
                true, "same", "same", Duration.ofMinutes(15), Duration.ofMinutes(30), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberSupportProperties(
                true, "proof", Base64.getEncoder().encodeToString(new byte[16]),
                Duration.ofMinutes(15), Duration.ofMinutes(30), true))
                .isInstanceOf(IllegalArgumentException.class);
        new MemberSupportProperties(true, "proof", key,
                Duration.ofMinutes(15), Duration.ofMinutes(30), true);
    }
}
