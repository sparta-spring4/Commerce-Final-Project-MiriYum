package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import org.junit.jupiter.api.Test;

class MemberSupportCatalogTest {

    @Test
    void exposesDistinctAppealAndPermanentApprovalPurposes() {
        assertThat(AdminCommandPurpose.valueOf("ACCOUNT_APPEAL_DECISION"))
                .isEqualTo(AdminCommandPurpose.ACCOUNT_APPEAL_DECISION);
        assertThat(AdminCommandPurpose.valueOf("PERMANENT_ACCOUNT_SANCTION_APPROVAL"))
                .isEqualTo(AdminCommandPurpose.PERMANENT_ACCOUNT_SANCTION_APPROVAL);
    }

    @Test
    void exposesNonEnumeratingMemberSupportErrors() {
        assertThat(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND.getCode()).isEqualTo("AUTH_016");
        assertThat(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT.getCode()).isEqualTo("AUTH_017");
        assertThat(AuthErrorCode.PERMANENT_SANCTION_APPROVAL_CONFLICT.getCode()).isEqualTo("AUTH_018");
    }

    @Test
    void exposesOnlyTheApprovedMemberSupportCatalogValues() {
        assertThat(MemberAccountType.values()).containsExactly(
                MemberAccountType.CONSUMER, MemberAccountType.STORE_OPERATOR);
        assertThat(MemberStatus.values()).containsExactly(
                MemberStatus.ACTIVE, MemberStatus.PASSWORD_RESET_REQUIRED, MemberStatus.FEATURE_RESTRICTED,
                MemberStatus.TEMPORARILY_SUSPENDED, MemberStatus.PERMANENTLY_SUSPENDED);
        assertThat(MemberSanctionLevel.values()).containsExactly(
                MemberSanctionLevel.WARNING, MemberSanctionLevel.FEATURE_RESTRICTION,
                MemberSanctionLevel.TEMPORARY_SUSPENSION, MemberSanctionLevel.PERMANENT_SUSPENSION);
        assertThat(RestrictedFeature.values()).containsExactly(
                RestrictedFeature.RESERVATION, RestrictedFeature.WAITING, RestrictedFeature.PICKUP,
                RestrictedFeature.STORE_OPERATION, RestrictedFeature.MENU_OPERATION);
    }
}
