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
    private String piiEncryptionKey = "";
    private Duration verificationTtl = Duration.ofMinutes(15);
    private Duration assignmentTtl = Duration.ofMinutes(30);
    private boolean devStubEnabled;

    public MemberSupportProperties() {
    }

    public MemberSupportProperties(boolean enabled, String proofDigestSecret, String piiEncryptionKey,
                                   Duration verificationTtl, Duration assignmentTtl, boolean devStubEnabled) {
        this.enabled = enabled;
        this.proofDigestSecret = proofDigestSecret;
        this.piiEncryptionKey = piiEncryptionKey;
        this.verificationTtl = verificationTtl;
        this.assignmentTtl = assignmentTtl;
        this.devStubEnabled = devStubEnabled;
        validate();
    }

    @PostConstruct
    void validate() {
        if (!enabled) return;
        if (proofDigestSecret == null || proofDigestSecret.isBlank()
                || piiEncryptionKey == null || piiEncryptionKey.isBlank()
                || proofDigestSecret.equals(piiEncryptionKey)) {
            throw new IllegalArgumentException("member-support secrets must be present and distinct");
        }
        try {
            if (Base64.getDecoder().decode(piiEncryptionKey).length != 32) {
                throw new IllegalArgumentException("member-support encryption key must be 32 bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("member-support encryption key must be base64 AES-256", exception);
        }
        if (verificationTtl == null || verificationTtl.isNegative() || verificationTtl.isZero()
                || assignmentTtl == null || assignmentTtl.isNegative() || assignmentTtl.isZero()) {
            throw new IllegalArgumentException("member-support TTLs must be positive");
        }
    }

    public boolean enabled() { return enabled; }
    public String proofDigestSecret() { return proofDigestSecret; }
    public String piiEncryptionKey() { return piiEncryptionKey; }
    public Duration verificationTtl() { return verificationTtl; }
    public Duration assignmentTtl() { return assignmentTtl; }
    public boolean devStubEnabled() { return devStubEnabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setProofDigestSecret(String value) { this.proofDigestSecret = value; }
    public void setPiiEncryptionKey(String value) { this.piiEncryptionKey = value; }
    public void setVerificationTtl(Duration value) { this.verificationTtl = value; }
    public void setAssignmentTtl(Duration value) { this.assignmentTtl = value; }
    public void setDevStubEnabled(boolean value) { this.devStubEnabled = value; }
}
