package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlatformOperatorCapabilitiesServiceTest {
    @Test
    void currentUsesCentralAuthoritySnapshotAndSortsCapabilities() {
        OperatorAuthorityReader authorities = mock(OperatorAuthorityReader.class);
        PlatformOperatorCapabilitiesService service = new PlatformOperatorCapabilitiesService(authorities);
        PlatformOperatorPrincipal principal =
                new PlatformOperatorPrincipal(1L, "caller@example.com", "secret-session", 3L, 8L, false);
        when(authorities.requireCurrentAuthority(1L, 3L)).thenReturn(new OperatorAuthority(
                1L, 3L,
                Set.of(PlatformOperatorRole.ONBOARDING_REVIEWER, PlatformOperatorRole.AUDIT_READER),
                Set.of(PlatformOperatorPermission.ONBOARDING_REVIEW,
                        PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ,
                        PlatformOperatorPermission.AUDIT_READ)));

        var result = service.current(principal);

        assertThat(result.authorityVersion()).isEqualTo(3L);
        assertThat(result.roles()).containsExactly(
                PlatformOperatorRole.AUDIT_READER, PlatformOperatorRole.ONBOARDING_REVIEWER);
        assertThat(result.permissions()).containsExactly(
                PlatformOperatorPermission.AUDIT_READ,
                PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ,
                PlatformOperatorPermission.ONBOARDING_REVIEW);
        assertThat(result.toString()).doesNotContain("secret-session", "caller@example.com");
    }
}
