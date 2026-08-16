package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportAudit;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MemberSupportAuditPrivacyTest {
    private static final String[] FORBIDDEN_FIELD_FRAGMENTS = {
            "email", "phone", "password", "token", "cookie", "otp",
            "businessnumber", "representativename", "rawapproval", "proof"
    };

    @Test
    void memberResponsesAndAuditLedgerHaveNoAuthenticationSecretOrUnnecessaryPiiFields() {
        assertNoSensitiveNames(MemberSupportResponses.MemberResponse.class.getRecordComponents());
        assertNoSensitiveNames(MemberSupportResponses.MemberPageResponse.class.getRecordComponents());
        assertNoSensitiveNames(MemberSupportResponses.CaseResponse.class.getRecordComponents());
        assertNoSensitiveNames(MemberSupportResponses.CasePageResponse.class.getRecordComponents());

        var auditFieldNames = Arrays.stream(MemberSupportAudit.class.getDeclaredFields())
                .map(field -> field.getName().toLowerCase(Locale.ROOT))
                .toList();
        assertThat(auditFieldNames).noneMatch(this::isForbidden);
    }

    private void assertNoSensitiveNames(RecordComponent[] components) {
        assertThat(Arrays.stream(components).map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT)))
                .noneMatch(this::isForbidden);
    }

    private boolean isForbidden(String name) {
        return Arrays.stream(FORBIDDEN_FIELD_FRAGMENTS).anyMatch(name::contains);
    }
}
