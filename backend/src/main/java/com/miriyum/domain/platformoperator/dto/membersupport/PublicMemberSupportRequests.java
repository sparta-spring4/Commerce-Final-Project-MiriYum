package com.miriyum.domain.platformoperator.dto.membersupport;

public final class PublicMemberSupportRequests {
    private PublicMemberSupportRequests() {
    }

    public record RecoveryVerificationCommand(String oldEmail, String registeredPhone, String newEmail) {
        @Override
        public String toString() {
            return "RecoveryVerificationCommand[REDACTED]";
        }
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
