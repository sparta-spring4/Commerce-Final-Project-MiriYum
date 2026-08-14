package com.miriyum.domain.platformoperator.dto.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class ReauthenticationApprovalRequestTest {
    @Test
    void acceptsAValidSixtyFourCodePointPasswordContainingSupplementaryCharacters() {
        String password = "Aa1" + "b".repeat(60) + "\uD801\uDC00";
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new ReauthenticationApprovalRequest(
                    password,
                    AdminCommandPurpose.PAYMENT_RECOVERY,
                    AdminTargetType.PAYMENT_RECOVERY_CASE,
                    "recovery-1");

            assertThat(password.codePointCount(0, password.length())).isEqualTo(64);
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void countsPasswordLengthAfterNfcNormalization() {
        String decomposed = "Aa1!" + "b".repeat(59) + "e\u0301";
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new ReauthenticationApprovalRequest(
                    decomposed,
                    AdminCommandPurpose.PAYMENT_RECOVERY,
                    AdminTargetType.PAYMENT_RECOVERY_CASE,
                    "recovery-1");

            assertThat(decomposed.codePointCount(0, decomposed.length())).isEqualTo(65);
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void rejectsMoreThanSixtyFourCodePointsAfterNfcNormalization() {
        String password = "Aa1!" + "b".repeat(61);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new ReauthenticationApprovalRequest(
                    password,
                    AdminCommandPurpose.PAYMENT_RECOVERY,
                    AdminTargetType.PAYMENT_RECOVERY_CASE,
                    "recovery-1");

            assertThat(password.codePointCount(0, password.length())).isEqualTo(65);
            assertThat(factory.getValidator().validate(request)).isNotEmpty();
        }
    }
}
