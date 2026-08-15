package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import org.springframework.http.ResponseCookie;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSupportCookieFactory {
    public static final String CONSUMER_RECOVERY_COOKIE = "miriyum_consumer_recovery_proof";
    public static final String STORE_OPERATOR_RECOVERY_COOKIE = "miriyum_store_operator_recovery_proof";
    public static final String CONSUMER_PASSWORD_RESET_COOKIE = "miriyum_consumer_password_reset_proof";
    public static final String STORE_OPERATOR_PASSWORD_RESET_COOKIE = "miriyum_store_operator_password_reset_proof";
    private final MemberSupportProperties properties;

    public MemberSupportCookieFactory(MemberSupportProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie recoveryProof(MemberAccountType accountType, String proof) {
        return proofCookie(recoveryCookieName(accountType), accountType, proof, properties.verificationTtl());
    }

    public ResponseCookie recoveryTrackingProof(MemberAccountType accountType, String proof) {
        return proofCookie(recoveryCookieName(accountType), accountType, proof, properties.recoveryTrackingTtl());
    }

    public ResponseCookie passwordResetProof(MemberAccountType accountType, String proof) {
        String name = accountType == MemberAccountType.CONSUMER
                ? CONSUMER_PASSWORD_RESET_COOKIE : STORE_OPERATOR_PASSWORD_RESET_COOKIE;
        return proofCookie(name, accountType, proof, properties.verificationTtl());
    }

    private String recoveryCookieName(MemberAccountType accountType) {
        String name = accountType == MemberAccountType.CONSUMER
                ? CONSUMER_RECOVERY_COOKIE : STORE_OPERATOR_RECOVERY_COOKIE;
        return name;
    }

    private ResponseCookie proofCookie(String name, MemberAccountType accountType, String proof, java.time.Duration ttl) {
        String path = accountType == MemberAccountType.CONSUMER
                ? "/api/v1/consumers" : "/api/v1/store-operators";
        return ResponseCookie.from(name, proof)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(path)
                .maxAge(ttl)
                .build();
    }
}
