package com.miriyum.domain.payment.controller.consumer;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistoryQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistorySlice;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 소비자의 결제 확정과 본인 결제·환불 조회 HTTP 경계다. */
@Validated
@RestController
@RequestMapping("/api/v1/consumers/me/payments")
@ConditionalOnProperty(name = "miriyum.payment.enabled", havingValue = "true")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public ApiResponse<PaymentHistorySlice> getPayments(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) PaymentStatus status
    ) {
        PaymentHistorySlice result = paymentService.getConsumerPaymentHistory(
                new PaymentHistoryQuery(principal.accountId(), status, size, cursor));
        return ApiResponse.success("조회했습니다.", result);
    }

    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentResult> getPayment(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String paymentId
    ) {
        PaymentResult result = paymentService.getOwnedPayment(
                paymentId,
                String.valueOf(principal.accountId())
        );
        return ApiResponse.success("조회했습니다.", result);
    }

    @PostMapping("/{paymentId}/confirmations")
    public ResponseEntity<ApiResponse<PaymentResult>> confirmPayment(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody PaymentConfirmationRequest request
    ) {
        PaymentResult result = paymentService.confirmPayment(new ConfirmPaymentCommand(
                paymentId,
                principal.accountId(),
                request.portOnePaymentId(),
                IdempotencyKey.parse(rawKey).value()
        ));
        boolean reconciliation = result.status() == PaymentStatus.RECONCILIATION_REQUIRED
                || result.status() == PaymentStatus.CONFIRMING;
        String message = reconciliation
                ? "결제 결과를 확인 중입니다."
                : "결제가 확인되었습니다.";
        return ResponseEntity.status(reconciliation ? 202 : 200)
                .body(ApiResponse.success(message, result));
    }

    public record PaymentConfirmationRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9_-]{6,64}$")
            String portOnePaymentId
    ) {
    }
}
