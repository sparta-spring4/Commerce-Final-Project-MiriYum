package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.payment.dto.PaymentContracts;
import com.miriyum.domain.payment.dto.PaymentMonitoringContracts;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.entity.PaymentLedgerEntry;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.payment.repository.PaymentLedgerEntryRepository;
import com.miriyum.domain.payment.repository.PaymentMonitoringSnapshotRepository;
import com.miriyum.domain.payment.repository.PaymentRefundRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class PaymentMonitoringQueryServiceTest {

    private static final Instant CREATED = Instant.parse("2026-08-19T05:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-08-19T06:00:00Z");
    private static final LocalDateTime AS_OF_DB = LocalDateTime.ofInstant(AS_OF, ZoneOffset.UTC);

    @Mock PaymentMonitoringSnapshotRepository snapshotRepository;
    @Mock PaymentLedgerEntryRepository ledgerRepository;
    @Mock PaymentRefundRepository refundRepository;

    private PaymentMonitoringQueryService service;

    @BeforeEach
    void setUp() {
        service = new PaymentMonitoringQueryService(
                snapshotRepository, ledgerRepository, refundRepository);
    }

    @Test
    void changedPaymentsMapToStableCasesAndUseDeterministicSeekOrder() {
        var reservation = snapshot("501", "RESERVATION_DEPOSIT", "91",
                Payment.Status.PAID, 4, AS_OF.minusSeconds(5));
        var waiting = snapshot("502", "WAITING_RESERVATION_DEPOSIT", "7",
                Payment.Status.PAID, 2, AS_OF.minusSeconds(5));
        given(snapshotRepository.findChangedCases(
                AS_OF_DB.minusSeconds(60), AS_OF_DB, AS_OF_DB, null, "PAID",
                null, null, 20))
                .willReturn(List.of(waiting, reservation));

        var result = service.findChangedCases(new PaymentMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF.minusSeconds(60), AS_OF, null,
                Set.of("PAID"), null, 20));

        assertThat(result.items()).extracting(PaymentMonitoringContracts.CaseReference::caseId)
                .containsExactly("waiting:7", "reservation-hold:91");
    }

    @Test
    void sourceFailureIsTypedAndCannotMasqueradeAsEmptyPage() {
        given(snapshotRepository.findChangedCases(
                AS_OF_DB.minusSeconds(60), AS_OF_DB, AS_OF_DB, null, "",
                null, null, 20))
                .willThrow(new DataAccessResourceFailureException("payment unavailable"));

        assertThatThrownBy(() -> service.findChangedCases(
                new PaymentMonitoringContracts.ChangeQuery(
                        AS_OF, AS_OF.minusSeconds(60), AS_OF, null, Set.of(), null, 20)))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(
                                PaymentErrorCode.MONITORING_SOURCE_UNAVAILABLE));
    }

    @Test
    void batchPreservesRawOptimisticVersionAndRefundReconciliation() {
        var payment = snapshot("501", "RESERVATION_DEPOSIT", "91",
                Payment.Status.RECONCILIATION_REQUIRED, 7, AS_OF.minusSeconds(2));
        given(snapshotRepository.findLatestCases(
                "RESERVATION_DEPOSIT", "91", AS_OF_DB))
                .willReturn(List.of(payment));

        var result = service.findCases(new PaymentMonitoringContracts.BatchQuery(
                AS_OF, List.of("reservation-hold:91")));

        assertThat(result.cells()).singleElement().satisfies(cell -> {
            assertThat(cell.state().statusVersion()).isEqualTo(7);
            assertThat(cell.reconciliationStatus())
                    .isEqualTo(PaymentMonitoringContracts.ReconciliationStatus.REQUIRED);
            assertThat(cell.state().paymentId()).isEqualTo("501");
        });
    }

    @Test
    void preBaselinePaymentIsUnavailableWithoutFabricatedState() {
        var existence = new PaymentMonitoringSnapshotRepository.Existence(
                "RESERVATION_DEPOSIT", "91", CREATED);
        given(snapshotRepository.findLatestCases(
                "RESERVATION_DEPOSIT", "91", AS_OF_DB))
                .willReturn(List.of());
        given(snapshotRepository.findExistingCases(
                "RESERVATION_DEPOSIT", "91", AS_OF_DB))
                .willReturn(List.of(existence));

        var cell = service.findCases(new PaymentMonitoringContracts.BatchQuery(
                AS_OF, List.of("reservation-hold:91"))).cells().getFirst();

        assertThat(cell.state()).isNull();
        assertThat(cell.completeness())
                .isEqualTo(PaymentMonitoringContracts.Completeness.UNAVAILABLE);
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
        given(snapshotRepository.findLatestCases(
                "WAITING_RESERVATION_DEPOSIT", "7", AS_OF_DB))
                .willReturn(List.of(payment));
        given(ledgerRepository.findMonitoringEvents(
                org.mockito.ArgumentMatchers.eq("501"),
                org.mockito.ArgumentMatchers.eq(AS_OF),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .willReturn(List.of(event));
        given(refundRepository.findMonitoringRefunds(
                org.mockito.ArgumentMatchers.eq("501"),
                org.mockito.ArgumentMatchers.eq(AS_OF),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
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

    private static PaymentMonitoringSnapshotRepository.Snapshot snapshot(
            String paymentId,
            String sourceType,
            String sourceReferenceId,
            Payment.Status status,
            long version,
            Instant updatedAt
    ) {
        String caseId = "RESERVATION_DEPOSIT".equals(sourceType)
                ? "reservation-hold:" + sourceReferenceId
                : "waiting:" + sourceReferenceId;
        return new PaymentMonitoringSnapshotRepository.Snapshot(
                caseId, paymentId, sourceType, sourceReferenceId, 12L,
                status, version, CREATED, updatedAt, 10000L,
                status == Payment.Status.REFUNDED ? 10000L : 0L, "KRW");
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
