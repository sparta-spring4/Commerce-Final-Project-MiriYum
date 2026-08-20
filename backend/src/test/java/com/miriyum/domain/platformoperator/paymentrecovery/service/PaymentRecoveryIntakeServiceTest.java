package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryAction;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryHandoffClaim;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryResultStatus;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentRecoveryIntakeServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Mock PaymentRecoveryCaseRepository cases;
    @Mock PaymentService payments;
    private PaymentRecoveryIntakeService service;

    @BeforeEach
    void setUp() {
        service = new PaymentRecoveryIntakeService(
                cases, payments, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsOneCaseFromPaymentOwnedHandoffAndReplaysItAfterCrash() {
        ManualRecoveryHandoffClaim claim = claim();
        ManualRecoveryInspection inspection = inspection();
        when(cases.findByHandoffId("21")).thenReturn(Optional.empty());
        when(payments.inspectManualRecovery(new com.miriyum.domain.payment.dto
                .PaymentRecoveryContracts.InspectManualRecoveryQuery("21")))
                .thenReturn(inspection);
        when(cases.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentRecoveryCase created = service.createOrReplay(claim);

        assertThat(created.getHandoffId()).isEqualTo("21");
        assertThat(created.getOriginalAmountMinor()).isEqualTo(300_000L);
        assertThat(created.getMaskedProviderReference()).isEqualTo("port********0001");
        verify(cases).saveAndFlush(created);

        when(cases.findByHandoffId("21")).thenReturn(Optional.of(created));
        assertThat(service.createOrReplay(claim)).isSameAs(created);
    }

    @Test
    void acknowledgementFailureReusesTheSameAdminCaseOnTheNextClaim() {
        ManualRecoveryHandoffClaim claim = claim();
        PaymentRecoveryCase created = org.mockito.Mockito.mock(PaymentRecoveryCase.class);
        PaymentRecoveryIntakeService intake = org.mockito.Mockito.mock(PaymentRecoveryIntakeService.class);
        when(created.getPublicId()).thenReturn("550e8400-e29b-41d4-a716-446655440281");
        when(payments.claimManualRecoveryHandoffs(
                new com.miriyum.domain.payment.dto.PaymentRecoveryContracts
                        .ClaimManualRecoveryHandoffsCommand("intake-worker", 10)))
                .thenReturn(List.of(claim), List.of(claim));
        when(intake.createOrReplay(claim)).thenReturn(created);
        var acknowledgement = new com.miriyum.domain.payment.dto.PaymentRecoveryContracts
                .AcknowledgeManualRecoveryHandoffCommand(
                "21", "intake-worker", 1L, created.getPublicId());
        doThrow(new IllegalStateException("ack lost"))
                .doNothing()
                .when(payments).acknowledgeManualRecoveryHandoff(acknowledgement);
        PaymentRecoveryIntakeJob job = new PaymentRecoveryIntakeJob(payments, intake);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> job.runOnce("intake-worker", 10))
                .isInstanceOf(IllegalStateException.class);
        job.runOnce("intake-worker", 10);

        verify(intake, times(2)).createOrReplay(claim);
        verify(payments, times(2)).acknowledgeManualRecoveryHandoff(acknowledgement);
    }

    private static ManualRecoveryHandoffClaim claim() {
        return new ManualRecoveryHandoffClaim(
                "21", ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND,
                "31", "intake-worker", 1L);
    }

    private static ManualRecoveryInspection inspection() {
        return new ManualRecoveryInspection(
                "21", 3L, 4L, 5L, ManualRecoveryKind.REFUND_RESULT_UNKNOWN,
                300_000L, 100_000L, 200_000L, "KRW",
                ManualRecoveryResultStatus.UNKNOWN,
                Set.of(ManualRecoveryAction.REQUERY_PROVIDER_RESULT, ManualRecoveryAction.RETRY_REFUND),
                "port********0001");
    }
}
