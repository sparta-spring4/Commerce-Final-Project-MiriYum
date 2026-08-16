package com.miriyum.domain.platformoperator.controller.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.ConsumerRecoveryVerificationRequest;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.RecoveryCaseSubmissionRequest;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.RecoveredPasswordResetRequest;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.StoreOperatorRecoveryVerificationRequest;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportCookieFactory;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportSubmissionService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberPasswordResetCredentialService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberRecoveryService;
import com.miriyum.domain.platformoperator.service.membersupport.MockMemberIdentityVerificationService;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExpression("'${miriyum.platform-operator.enabled:false}' == 'true' and "
        + "'${miriyum.member-support.enabled:false}' == 'true'")
public class MemberRecoveryController {
    private static final ApiResponse<Void> ACCEPTED = ApiResponse.success("요청을 접수했습니다.", null);
    private final MockMemberIdentityVerificationService verifications;
    private final MemberSupportSubmissionService submissions;
    private final MemberSupportCookieFactory cookies;
    private final MemberRecoveryService recovery;
    private final MemberPasswordResetCredentialService resetCredentials;

    public MemberRecoveryController(MockMemberIdentityVerificationService verifications,
                                    MemberSupportSubmissionService submissions,
                                    MemberSupportCookieFactory cookies,
                                    MemberRecoveryService recovery,
                                    MemberPasswordResetCredentialService resetCredentials) {
        this.verifications = verifications;
        this.submissions = submissions;
        this.cookies = cookies;
        this.recovery = recovery;
        this.resetCredentials = resetCredentials;
    }

    @PostMapping("/api/v1/consumers/account-recovery-verifications")
    public ResponseEntity<ApiResponse<Void>> verifyConsumer(
            @Valid @RequestBody ConsumerRecoveryVerificationRequest request) {
        return verificationResponse(MemberAccountType.CONSUMER,
                verifications.issueRecovery(MemberAccountType.CONSUMER, request.toCommand()).value());
    }

    @PostMapping("/api/v1/store-operators/account-recovery-verifications")
    public ResponseEntity<ApiResponse<Void>> verifyStoreOperator(
            @Valid @RequestBody StoreOperatorRecoveryVerificationRequest request) {
        return verificationResponse(MemberAccountType.STORE_OPERATOR,
                verifications.issueRecovery(MemberAccountType.STORE_OPERATOR, request.toCommand()).value());
    }

    @PostMapping("/api/v1/consumers/account-recovery-cases")
    public ResponseEntity<ApiResponse<Void>> submitConsumer(
            @CookieValue(name = MemberSupportCookieFactory.CONSUMER_RECOVERY_COOKIE, required = false) String proof,
            @Valid @RequestBody RecoveryCaseSubmissionRequest request) {
        submissions.submitRecovery(MemberAccountType.CONSUMER, proof, request.newEmail());
        return trackingResponse(MemberAccountType.CONSUMER, proof);
    }

    @PostMapping("/api/v1/store-operators/account-recovery-cases")
    public ResponseEntity<ApiResponse<Void>> submitStoreOperator(
            @CookieValue(name = MemberSupportCookieFactory.STORE_OPERATOR_RECOVERY_COOKIE, required = false) String proof,
            @Valid @RequestBody RecoveryCaseSubmissionRequest request) {
        submissions.submitRecovery(MemberAccountType.STORE_OPERATOR, proof, request.newEmail());
        return trackingResponse(MemberAccountType.STORE_OPERATOR, proof);
    }

    @PostMapping("/api/v1/consumers/account-recovery-password-reset-credentials")
    public ResponseEntity<ApiResponse<Void>> exchangeConsumerResetCredential(
            @CookieValue(name = MemberSupportCookieFactory.CONSUMER_RECOVERY_COOKIE, required = false) String proof) {
        return resetCredentialResponse(MemberAccountType.CONSUMER, proof);
    }

    @PostMapping("/api/v1/store-operators/account-recovery-password-reset-credentials")
    public ResponseEntity<ApiResponse<Void>> exchangeStoreOperatorResetCredential(
            @CookieValue(name = MemberSupportCookieFactory.STORE_OPERATOR_RECOVERY_COOKIE, required = false) String proof) {
        return resetCredentialResponse(MemberAccountType.STORE_OPERATOR, proof);
    }

    @PostMapping("/api/v1/consumers/account-recovery-password-resets")
    public ResponseEntity<Void> resetConsumerPassword(
            @CookieValue(name = MemberSupportCookieFactory.CONSUMER_PASSWORD_RESET_COOKIE, required = false) String proof,
            @Valid @RequestBody RecoveredPasswordResetRequest request) {
        recovery.completePasswordReset(proof, MemberAccountType.CONSUMER, request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/store-operators/account-recovery-password-resets")
    public ResponseEntity<Void> resetStoreOperatorPassword(
            @CookieValue(name = MemberSupportCookieFactory.STORE_OPERATOR_PASSWORD_RESET_COOKIE, required = false) String proof,
            @Valid @RequestBody RecoveredPasswordResetRequest request) {
        recovery.completePasswordReset(proof, MemberAccountType.STORE_OPERATOR, request.newPassword());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<ApiResponse<Void>> verificationResponse(MemberAccountType type, String proof) {
        return ResponseEntity.accepted()
                .header(HttpHeaders.SET_COOKIE, cookies.recoveryProof(type, proof).toString())
                .body(ACCEPTED);
    }

    private ResponseEntity<ApiResponse<Void>> trackingResponse(MemberAccountType type, String proof) {
        String opaque = proof == null || proof.isBlank() ? resetCredentials.placeholder().value() : proof;
        return ResponseEntity.accepted()
                .header(HttpHeaders.SET_COOKIE, cookies.recoveryTrackingProof(type, opaque).toString())
                .body(ACCEPTED);
    }

    private ResponseEntity<ApiResponse<Void>> resetCredentialResponse(MemberAccountType type, String trackingProof) {
        String proof = resetCredentials.exchange(type, trackingProof)
                .orElseGet(resetCredentials::placeholder).value();
        return ResponseEntity.accepted()
                .header(HttpHeaders.SET_COOKIE, cookies.passwordResetProof(type, proof).toString())
                .body(ACCEPTED);
    }
}
