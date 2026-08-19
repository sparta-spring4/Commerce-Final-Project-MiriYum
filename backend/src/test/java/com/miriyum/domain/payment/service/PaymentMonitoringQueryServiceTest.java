package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.payment.dto.PaymentContracts;
import com.miriyum.domain.payment.dto.PaymentMonitoringContracts;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.entity.PaymentLedgerEntry;
import com.miriyum.domain.payment.repository.PaymentLedgerEntryRepository;
import com.miriyum.domain.payment.repository.PaymentRefundRepository;
import com.miriyum.domain.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class PaymentMonitoringQueryServiceTest {

    private static final Instant CREATED = Instant.parse("2026-08-19T05:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-08-19T06:00:00Z");

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentLedgerEntryRepository ledgerRepository;
    @Mock PaymentRefundRepository refundRepository;

    private PaymentMonitoringQueryService service;

    @BeforeEach
    void setUp() {
        service = new PaymentMonitoringQueryService(
                paymentRepository, ledgerRepository, refundRepository);
    }

    @Test
    void changedPaymentsMapToStableCasesAndUseDeterministicSeekOrder() {
        var reservation = snapshot("501", "RESERVATION_DEPOSIT", "91",
                Payment.Status.PAID, 4, AS_OF.minusSeconds(5));
        var waiting = snapshot("502", "WAITING_RESERVATION_DEPOSIT", "7",
                Payment.Status.PAID, 2, AS_OF.minusSeconds(5));
        given(paymentRepository.findMonitoringChanges(
                AS_OF.minusSeconds(60), AS_OF, Pageable.ofSize(100)))
                .willReturn(List.of(waiting, reservation));

        var result = service.findChangedCases(new PaymentMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF.minusSeconds(60), AS_OF, null,
                Set.of("PAID"), null, 20));

        assertThat(result.items()).extracting(PaymentMonitoringContracts.CaseReference::caseId)
                .containsExactly("waiting:7", "reservation-hold:91");
    }

    @Test
    void batchPreservesRawOptimisticVersionAndRefundReconciliation() {
        var payment = snapshot("501", "RESERVATION_DEPOSIT", "91",
                Payment.Status.RECONCILIATION_REQUIRED, 7, AS_OF.minusSeconds(2));
        given(paymentRepository.findMonitoringSnapshots(List.of("91"), List.of()))
                .willReturn(List.of(payment));

        var result = service.findCases(new PaymentMonitoringContracts.BatchQuery(
                AS_OF, List.of("reservation-hold:91")));

        assertThat(result.cells()).singleElement().satisfies(cell -> {
            assertThat(cell.statusVersion()).isEqualTo(7);
            assertThat(cell.reconciliationStatus())
                    .isEqualTo(PaymentMonitoringContracts.ReconciliationStatus.REQUIRED);
            assertThat(cell.paymentId()).isEqualTo("501");
        });
    }

    @Test
    void snapshotUpdatedAfterAsOfIsNotPresentedAsLatestConfirmedState() {
        var future = snapshot("501", "RESERVATION_DEPOSIT", "91",
                Payment.Status.REFUNDED, 8, AS_OF.plusSeconds(10));
        var confirmed = event("501", PaymentLedgerEntry.Type.PAYMENT_CONFIRMED,
                10000, AS_OF.minusSeconds(30));
        given(paymentRepository.findMonitoringSnapshots(List.of("91"), List.of()))
                .willReturn(List.of(future));
        given(ledgerRepository.findMonitoringEvents(List.of("501")))
                .willReturn(List.of(confirmed));

        var cell = service.findCases(new PaymentMonitoringContracts.BatchQuery(
                AS_OF, List.of("reservation-hold:91"))).cells().getFirst();

        assertThat(cell.sourceStatus()).isEqualTo("UNAVAILABLE");
        assertThat(cell.completeness())
                .isEqualTo(PaymentMonitoringContracts.Completeness.UNAVAILABLE);
        assertThat(cell.statusVersion()).isZero();
    }

    @Test
    void detailUsesStoredLedgerAndRefundProjectionWithoutProviderSecrets() {
        var payment = snapshot("501", "WAITING_RESERVATION_DEPOSIT", "7",
                Payment.Status.REFUNDED, 6, AS_OF.minusSeconds(2));
        var event = event("501", PaymentLedgerEntry.Type.REFUND_COMPLETED,
                10000, AS_OF.minusSeconds(2));
        var refund = mock(PaymentRefundRepository.MonitoringRefund.class);
        given(refund.getStatus()).willReturn(PaymentContracts.RefundStatus.COMPLETED);
        given(refund.getVersion()).willReturn(2L);
        given(refund.getAmountMinor()).willReturn(10000L);
        given(refund.getRequestedAt()).willReturn(AS_OF.minusSeconds(20));
        given(refund.getCompletedAt()).willReturn(AS_OF.minusSeconds(2));
        given(refund.getUpdatedAt()).willReturn(AS_OF.minusSeconds(2));
        given(paymentRepository.findMonitoringSnapshots(List.of(), List.of("7")))
                .willReturn(List.of(payment));
        given(ledgerRepository.findMonitoringEvents(List.of("501")))
                .willReturn(List.of(event));
        given(refundRepository.findMonitoringRefunds(List.of("501")))
                .willReturn(List.of(refund));

        var detail = service.findCase(new PaymentMonitoringContracts.DetailQuery(
                AS_OF, "waiting:7")).orElseThrow();

        assertThat(detail.ledger()).singleElement().satisfies(item ->
                assertThat(item.eventType()).isEqualTo("REFUND_COMPLETED"));
        assertThat(detail.refunds()).singleElement().satisfies(item -> {
            assertThat(item.sourceStatus()).isEqualTo("COMPLETED");
            assertThat(item.statusVersion()).isEqualTo(2);
        });
    }

    private static PaymentRepository.MonitoringSnapshot snapshot(
            String paymentId,
            String sourceType,
            String sourceReferenceId,
            Payment.Status status,
            long version,
            Instant updatedAt
    ) {
        var snapshot = mock(PaymentRepository.MonitoringSnapshot.class);
        lenient().when(snapshot.getPaymentId()).thenReturn(paymentId);
        lenient().when(snapshot.getSourceType()).thenReturn(sourceType);
        lenient().when(snapshot.getSourceReferenceId()).thenReturn(sourceReferenceId);
        lenient().when(snapshot.getStatus()).thenReturn(status);
        lenient().when(snapshot.getVersion()).thenReturn(version);
        lenient().when(snapshot.getCreatedAt()).thenReturn(CREATED);
        lenient().when(snapshot.getUpdatedAt()).thenReturn(updatedAt);
        lenient().when(snapshot.getAmountMinor()).thenReturn(10000L);
        lenient().when(snapshot.getRefundedAmountMinor()).thenReturn(
                status == Payment.Status.REFUNDED ? 10000L : 0L);
        lenient().when(snapshot.getCurrency()).thenReturn("KRW");
        return snapshot;
    }

    private static PaymentLedgerEntryRepository.MonitoringEvent event(
            String paymentId,
            PaymentLedgerEntry.Type type,
            long amountMinor,
            Instant occurredAt
    ) {
        var event = mock(PaymentLedgerEntryRepository.MonitoringEvent.class);
        lenient().when(event.getPaymentId()).thenReturn(paymentId);
        lenient().when(event.getType()).thenReturn(type);
        lenient().when(event.getAmountMinor()).thenReturn(amountMinor);
        lenient().when(event.getOccurredAt()).thenReturn(occurredAt);
        return event;
    }
}
