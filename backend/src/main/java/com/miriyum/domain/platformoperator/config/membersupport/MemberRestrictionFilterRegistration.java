package com.miriyum.domain.platformoperator.config.membersupport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberRestrictionFilterRegistration {
    @Bean
    FilterRegistrationBean<MemberRestrictionFilter> disableContainerRegistration(MemberRestrictionFilter filter) {
        FilterRegistrationBean<MemberRestrictionFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
