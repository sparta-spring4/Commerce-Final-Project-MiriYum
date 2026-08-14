package com.miriyum.domain.platformoperator.dto.membersupport;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PublicMemberSupportRequests {
    private PublicMemberSupportRequests() {
    }

    public record RecoveryVerificationCommand(String oldEmail, String registeredPhone, String newEmail) {
        @Override
        public String toString() {
            return "RecoveryVerificationCommand[REDACTED]";
        }
    }

    public record ConsumerRecoveryVerificationRequest(
            @NotBlank @Email @Size(max = 254) String oldEmail,
            @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String registeredPhone,
            @NotBlank @Email @Size(max = 254) String newEmail
    ) {
        public RecoveryVerificationCommand toCommand() {
            return new RecoveryVerificationCommand(oldEmail, registeredPhone, newEmail);
        }

        @Override public String toString() { return "ConsumerRecoveryVerificationRequest[REDACTED]"; }
    }

    public record StoreOperatorRecoveryVerificationRequest(
            @NotBlank @Email @Size(max = 254) String oldEmail,
            @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String registeredPhone,
            @NotBlank @Email @Size(max = 254) String newEmail,
            @NotBlank @Size(min = 10, max = 32) String businessRegistrationNumber,
            @NotBlank @Size(max = 100) String representativeName
    ) {
        public RecoveryVerificationCommand toCommand() {
            return new RecoveryVerificationCommand(oldEmail, registeredPhone, newEmail);
        }

        @Override public String toString() { return "StoreOperatorRecoveryVerificationRequest[REDACTED]"; }
    }

    public record RecoveryCaseSubmissionRequest(@NotBlank @Email @Size(max = 254) String newEmail) {
        @Override public String toString() { return "RecoveryCaseSubmissionRequest[REDACTED]"; }
    }

    public record RecoveredPasswordResetRequest(
            @NotBlank @Size(min = 8, max = 64) String newPassword
    ) {
        @Override public String toString() { return "RecoveredPasswordResetRequest[REDACTED]"; }
    }

    public enum VerificationChannel { REGISTERED_PHONE, REGISTERED_EMAIL }

    public record AppealSubmissionRequest(
            @NotBlank @Size(max = 100) String sanctionId,
            VerificationChannel verificationChannel,
            @NotBlank @Size(max = 254) String contact,
            @NotBlank @Size(max = 2000) String statement
    ) {
        @Override public String toString() { return "AppealSubmissionRequest[REDACTED]"; }
    }

    public record OpaqueProof(String value) {
        @Override
        public String toString() {
            return "OpaqueProof[REDACTED]";
        }
    }

    public record ConsumedVerification(long verificationId, long accountId, String newEmail) {
        @Override
        public String toString() {
            return "ConsumedVerification[verificationId=" + verificationId + ", accountId=" + accountId
                    + ", newEmail=REDACTED]";
        }
    }
}
