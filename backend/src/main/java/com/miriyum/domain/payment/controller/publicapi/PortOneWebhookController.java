package com.miriyum.domain.payment.controller.publicapi;

import com.miriyum.domain.payment.service.PaymentWebhookService;
import com.miriyum.domain.payment.service.PaymentWebhookService.WebhookResult;
import com.miriyum.global.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PortOne V2 Webhook의 raw body와 Standard Webhooks 헤더를 수신한다. */
@RestController
@RequestMapping("/api/v1/payments/webhooks")
@ConditionalOnProperty(name = "miriyum.payment.enabled", havingValue = "true")
public class PortOneWebhookController {

    private final PaymentWebhookService webhookService;

    public PortOneWebhookController(PaymentWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/portone")
    public ResponseEntity<ApiResponse<Void>> receive(
            @RequestBody String rawBody,
            @RequestHeader("webhook-id") String messageId,
            @RequestHeader("webhook-timestamp") String messageTimestamp,
            @RequestHeader("webhook-signature") String messageSignature
    ) {
        WebhookResult result = webhookService.handle(
                rawBody, messageId, messageTimestamp, messageSignature);
        boolean reconciliation = result == WebhookResult.RECONCILIATION_REQUIRED;
        return ResponseEntity.status(reconciliation ? 202 : 200)
                .body(ApiResponse.success("Webhook을 처리했습니다.", null));
    }
}
