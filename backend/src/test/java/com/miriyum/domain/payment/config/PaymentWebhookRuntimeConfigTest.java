package com.miriyum.domain.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.payment.controller.publicapi.PortOneWebhookController;
import com.miriyum.domain.payment.service.PaymentWebhookService;
import com.miriyum.global.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@WebMvcTest(
        controllers = PortOneWebhookController.class,
        properties = {
                "miriyum.payment.enabled=true",
                "miriyum.payment.portone.webhook-enabled=true"
        }
)
@Import({PaymentSecurityConfig.class, SecurityConfig.class})
class PaymentWebhookRuntimeConfigTest extends PaymentWebhookRuntimeConfigTestSupport {

    @Test
    void registersControllerAndWebhookSecurityChainWhenBothRuntimeFlagsAreEnabled() {
        assertThat(context.getBeansOfType(PortOneWebhookController.class)).hasSize(1);
        assertThat(context.containsBean("paymentWebhookFilterChain")).isTrue();
        assertThat(context.containsBean("disabledPaymentWebhookFilterChain")).isFalse();
    }
}

@WebMvcTest(
        controllers = PortOneWebhookController.class,
        properties = {
                "miriyum.payment.enabled=true",
                "miriyum.payment.portone.webhook-enabled=false"
        }
)
@Import({PaymentSecurityConfig.class, SecurityConfig.class})
class PaymentOnlyWebhookRuntimeConfigTest extends PaymentWebhookRuntimeConfigTestSupport {

    @Test
    void doesNotRegisterControllerOrActiveWebhookSecurityChainWhenOnlyPaymentIsEnabled() {
        assertWebhookIsDisabled(context);
    }
}

@WebMvcTest(
        controllers = PortOneWebhookController.class,
        properties = {
                "miriyum.payment.enabled=false",
                "miriyum.payment.portone.webhook-enabled=true"
        }
)
@Import({PaymentSecurityConfig.class, SecurityConfig.class})
class WebhookOnlyRuntimeConfigTest extends PaymentWebhookRuntimeConfigTestSupport {

    @Test
    void doesNotRegisterControllerOrActiveWebhookSecurityChainWhenOnlyWebhookIsEnabled() {
        assertWebhookIsDisabled(context);
    }
}

@WebMvcTest(
        controllers = PortOneWebhookController.class,
        properties = {
                "miriyum.payment.enabled=false",
                "miriyum.payment.portone.webhook-enabled=false"
        }
)
@Import({PaymentSecurityConfig.class, SecurityConfig.class})
class PaymentAndWebhookDisabledRuntimeConfigTest extends PaymentWebhookRuntimeConfigTestSupport {

    @Test
    void doesNotRegisterControllerOrActiveWebhookSecurityChainWhenBothRuntimeFlagsAreDisabled() {
        assertWebhookIsDisabled(context);
    }
}

abstract class PaymentWebhookRuntimeConfigTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    protected ApplicationContext context;

    @MockitoBean
    private PaymentWebhookService webhookService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RateLimiter rateLimiter;

    protected void assertWebhookIsDisabled(ApplicationContext context) {
        assertThat(context.getBeansOfType(PortOneWebhookController.class)).isEmpty();
        assertThat(context.containsBean("paymentWebhookFilterChain")).isFalse();
        assertThat(context.containsBean("disabledPaymentWebhookFilterChain")).isTrue();
    }
}
