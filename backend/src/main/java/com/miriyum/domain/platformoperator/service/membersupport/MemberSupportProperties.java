package com.miriyum.domain.platformoperator.service.membersupport;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "miriyum.member-support")
public class MemberSupportProperties {
    private boolean enabled;
    private String proofDigestSecret = "";
    private int piiEncryptionActiveKeyVersion;
    private String piiEncryptionActiveKey = "";
    private int piiEncryptionPreviousKeyVersion;
    private String piiEncryptionPreviousKey = "";
    private Duration verificationTtl = Duration.ofMinutes(15);
    private Duration recoveryCompletionTtl = Duration.ofDays(30);
    private Duration recoveryTrackingTtl = Duration.ofDays(365);
    private Duration assignmentTtl = Duration.ofMinutes(30);
    private boolean devStubEnabled;

    public MemberSupportProperties() {
    }

    public MemberSupportProperties(boolean enabled, String proofDigestSecret, String piiEncryptionKey,
                                   Duration verificationTtl, Duration assignmentTtl, boolean devStubEnabled) {
        this.enabled = enabled;
        this.proofDigestSecret = proofDigestSecret;
        this.piiEncryptionActiveKeyVersion = 1;
        this.piiEncryptionActiveKey = piiEncryptionKey;
        this.verificationTtl = verificationTtl;
        this.assignmentTtl = assignmentTtl;
        this.devStubEnabled = devStubEnabled;
        validate();
    }

    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (proofDigestSecret == null || proofDigestSecret.isBlank()
                || piiEncryptionActiveKey == null || piiEncryptionActiveKey.isBlank()
                || proofDigestSecret.equals(piiEncryptionActiveKey)) {
            throw new IllegalArgumentException("member-support secrets must be present and distinct");
        }
        validateVersion(piiEncryptionActiveKeyVersion, "active");
        validateAes256Key(piiEncryptionActiveKey, "active");
        boolean hasPreviousVersion = piiEncryptionPreviousKeyVersion != 0;
        boolean hasPreviousKey = piiEncryptionPreviousKey != null && !piiEncryptionPreviousKey.isBlank();
        if (hasPreviousVersion != hasPreviousKey) {
            throw new IllegalArgumentException("member-support previous encryption key requires version and key");
        }
        if (hasPreviousVersion) {
            validateVersion(piiEncryptionPreviousKeyVersion, "previous");
            validateAes256Key(piiEncryptionPreviousKey, "previous");
            if (piiEncryptionPreviousKeyVersion == piiEncryptionActiveKeyVersion
                    || piiEncryptionPreviousKey.equals(piiEncryptionActiveKey)
                    || proofDigestSecret.equals(piiEncryptionPreviousKey)) {
                throw new IllegalArgumentException("member-support active and previous encryption keys must be distinct");
            }
        }
        if (verificationTtl == null || verificationTtl.isNegative() || verificationTtl.isZero()
                || recoveryCompletionTtl == null || recoveryCompletionTtl.isNegative() || recoveryCompletionTtl.isZero()
                || recoveryTrackingTtl == null || recoveryTrackingTtl.isNegative() || recoveryTrackingTtl.isZero()
                || assignmentTtl == null || assignmentTtl.isNegative() || assignmentTtl.isZero()) {
            throw new IllegalArgumentException("member-support TTLs must be positive");
        }
    }

    private void validateVersion(int version, String label) {
        if (version < 1 || version > 255) {
            throw new IllegalArgumentException("member-support " + label + " encryption key version must be 1..255");
        }
    }

    private void validateAes256Key(String value, String label) {
        try {
            if (Base64.getDecoder().decode(value).length != 32) {
                throw new IllegalArgumentException("member-support encryption key must be 32 bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "member-support " + label + " encryption key must be base64 AES-256", exception);
        }
    }

    public boolean enabled() { return enabled; }
    public String proofDigestSecret() { return proofDigestSecret; }
    public int piiEncryptionActiveKeyVersion() { return piiEncryptionActiveKeyVersion; }
    public String piiEncryptionActiveKey() { return piiEncryptionActiveKey; }
    public int piiEncryptionPreviousKeyVersion() { return piiEncryptionPreviousKeyVersion; }
    public String piiEncryptionPreviousKey() { return piiEncryptionPreviousKey; }
    public Duration verificationTtl() { return verificationTtl; }
    public Duration recoveryCompletionTtl() { return recoveryCompletionTtl; }
    public Duration recoveryTrackingTtl() { return recoveryTrackingTtl; }
    public Duration assignmentTtl() { return assignmentTtl; }
    public boolean devStubEnabled() { return devStubEnabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setProofDigestSecret(String value) { this.proofDigestSecret = value; }
    public void setPiiEncryptionActiveKeyVersion(int value) { this.piiEncryptionActiveKeyVersion = value; }
    public void setPiiEncryptionActiveKey(String value) { this.piiEncryptionActiveKey = value; }
    public void setPiiEncryptionPreviousKeyVersion(int value) { this.piiEncryptionPreviousKeyVersion = value; }
    public void setPiiEncryptionPreviousKey(String value) { this.piiEncryptionPreviousKey = value; }
    public void setVerificationTtl(Duration value) { this.verificationTtl = value; }
    public void setRecoveryCompletionTtl(Duration value) { this.recoveryCompletionTtl = value; }
    public void setRecoveryTrackingTtl(Duration value) { this.recoveryTrackingTtl = value; }
    public void setAssignmentTtl(Duration value) { this.assignmentTtl = value; }
    public void setDevStubEnabled(boolean value) { this.devStubEnabled = value; }
}
