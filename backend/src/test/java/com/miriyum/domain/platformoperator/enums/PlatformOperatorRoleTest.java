package com.miriyum.domain.platformoperator.enums;

import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.AUDIT_READ;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.MEMBER_READ_MINIMAL;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.OPERATOR_CREATE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.OPERATOR_SUSPEND;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformOperatorRoleTest {

    @Test
    @DisplayName("슈퍼관리자는 운영자 생명주기 권한만 가지며 포괄 조회 권한을 얻지 않는다")
    void superAdminDoesNotImplicitlyReceiveBroadReadPermissions() {
        assertThat(PlatformOperatorRole.SUPER_ADMIN.permissions())
                .contains(OPERATOR_CREATE, OPERATOR_AUTHORITY_MANAGE, OPERATOR_SUSPEND)
                .doesNotContain(MEMBER_READ_MINIMAL, ONBOARDING_EVIDENCE_READ, AUDIT_READ);
    }

    @Test
    @DisplayName("모든 역할 권한 묶음은 비어 있지 않고 수정할 수 없다")
    void everyRoleHasAnImmutablePermissionBundle() {
        for (PlatformOperatorRole role : PlatformOperatorRole.values()) {
            assertThat(role.permissions()).isNotEmpty();
            assertThatThrownByMutation(role);
        }
    }

    private static void assertThatThrownByMutation(PlatformOperatorRole role) {
        PlatformOperatorPermission permission = role.permissions().iterator().next();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> role.permissions().remove(permission))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
