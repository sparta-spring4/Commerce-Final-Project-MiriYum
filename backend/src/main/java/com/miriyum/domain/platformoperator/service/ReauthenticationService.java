package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.ReauthenticationApprovalRequest;
import com.miriyum.domain.platformoperator.dto.authorization.ReauthenticationApprovalResult;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorReauthenticationApproval;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPasswordState;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorReauthenticationApprovalRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 비밀번호를 확인해 현재 세션과 명령에 결속된 5분 일회 승인을 발급한다. */
@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class ReauthenticationService {
    private static final Duration APPROVAL_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PlatformOperatorAccountRepository accounts;
    private final PlatformOperatorReauthenticationApprovalRepository approvals;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final byte[] fingerprintKey;

    public ReauthenticationService(
            PlatformOperatorAccountRepository accounts,
            PlatformOperatorReauthenticationApprovalRepository approvals,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${miriyum.jwt.secret}") String fingerprintSecret
    ) {
        this.accounts = accounts;
        this.approvals = approvals;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.fingerprintKey = fingerprintSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public ReauthenticationApprovalResult issue(
            PlatformOperatorPrincipal principal,
            ReauthenticationApprovalRequest request
    ) {
        PlatformOperatorAccount account = accounts.findById(principal.accountId())
                .filter(candidate -> candidate.getStatus() == PlatformOperatorAccountStatus.ACTIVE)
                .filter(candidate -> candidate.getPasswordState() == PlatformOperatorPasswordState.ACTIVE)
                .filter(candidate -> candidate.getAuthorityVersion() == principal.authorityVersion())
                .filter(candidate -> candidate.getSessionVersion() == principal.sessionVersion())
                .orElseThrow(() -> new ServiceException(AdminAuthorizationErrorCode.REAUTHENTICATION_FAILED));
        if (!matches(request.currentPassword(), account.getPasswordHash())) {
            throw new ServiceException(AdminAuthorizationErrorCode.REAUTHENTICATION_FAILED);
        }

        String plaintext = newApproval();
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(APPROVAL_TTL);
        approvals.save(PlatformOperatorReauthenticationApproval.issue(
                sha256(plaintext),
                principal.accountId(),
                request.purpose(),
                request.targetType(),
                request.targetId(),
                sessionFingerprint(principal.sessionId()),
                principal.authorityVersion(),
                issuedAt,
                expiresAt));
        return new ReauthenticationApprovalResult(plaintext, expiresAt);
    }

    String sessionFingerprint(String sessionId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
            return hex(mac.doFinal(("platform-operator-reauth-session:" + sessionId)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("session fingerprint algorithm unavailable", exception);
        }
    }

    static String sha256(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String newApproval() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean matches(String raw, String encoded) {
        try {
            return passwordEncoder.matches(raw, encoded);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
