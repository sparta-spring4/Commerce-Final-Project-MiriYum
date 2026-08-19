package com.miriyum.domain.payment.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.payment.dto.PaymentMonitoringContracts;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentMonitoringPublicContractTest {

    private static final Instant AS_OF = Instant.parse("2026-08-19T06:00:00Z");

    @Test
    void publicRecordsExposeNoEntityRepositoryProviderOrSecretTypes() {
        for (Class<?> nested : PaymentMonitoringContracts.class.getDeclaredClasses()) {
            if (!nested.isRecord()) continue;
            assertThat(Arrays.stream(nested.getRecordComponents())
                    .map(RecordComponent::getGenericType)
                    .map(Object::toString))
                    .allMatch(type -> !type.contains(".entity.")
                            && !type.contains(".repository.")
                            && !type.toLowerCase().contains("provider")
                            && !type.toLowerCase().contains("transaction"));
        }
    }

    @Test
    void queriesAreBoundedAndAcceptReservationAndWaitingCases() {
        var query = new PaymentMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF.minusSeconds(60), AS_OF, null,
                Set.of("PAID"), null, 100);

        assertThat(query.sourceStatuses()).containsExactly("PAID");
        assertThat(new PaymentMonitoringContracts.BatchQuery(
                AS_OF, List.of("reservation-hold:91", "waiting:7")).caseIds())
                .hasSize(2);
        assertThatThrownBy(() -> new PaymentMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF, AS_OF.plusSeconds(1), null, Set.of(), null, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentMonitoringContracts.BatchQuery(
                AS_OF, List.of("payment:1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cellPreservesRawVersionAndNeverExposesPaymentSecrets() {
        var cell = new PaymentMonitoringContracts.SourceCell(
                "reservation-hold:91",
                new PaymentMonitoringContracts.ConfirmedState(
                        "501", "PAID", 4, AS_OF.minusSeconds(5),
                        10000, 0, "KRW"),
                AS_OF, AS_OF,
                PaymentMonitoringContracts.Completeness.COMPLETE,
                PaymentMonitoringContracts.ReconciliationStatus.MATCHED,
                AS_OF.minusSeconds(30));

        assertThat(cell.state().statusVersion()).isEqualTo(4);
        assertThat(Arrays.stream(cell.state().getClass().getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("portOnePaymentId", "providerTransactionId",
                        "paymentKey", "approvalToken", "paymentMethod");
        assertThatThrownBy(() -> new PaymentMonitoringContracts.SourceCell(
                "reservation-hold:91",
                new PaymentMonitoringContracts.ConfirmedState(
                        "501", "PAID", 4, AS_OF, 10000, 0, "KRW"),
                AS_OF, AS_OF.minusSeconds(1),
                PaymentMonitoringContracts.Completeness.COMPLETE,
                PaymentMonitoringContracts.ReconciliationStatus.MATCHED,
                AS_OF.minusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unavailableCellCannotFabricatePaymentState() {
        var cell = new PaymentMonitoringContracts.SourceCell(
                "reservation-hold:91", null, AS_OF, AS_OF,
                PaymentMonitoringContracts.Completeness.UNAVAILABLE,
                PaymentMonitoringContracts.ReconciliationStatus.UNKNOWN,
                AS_OF.plusSeconds(1));

        assertThat(cell.state()).isNull();
        assertThatThrownBy(() -> new PaymentMonitoringContracts.SourceCell(
                "reservation-hold:91",
                new PaymentMonitoringContracts.ConfirmedState(
                        "501", "PAID", 0, AS_OF, 10000, 0, "KRW"),
                AS_OF, AS_OF,
                PaymentMonitoringContracts.Completeness.UNAVAILABLE,
                PaymentMonitoringContracts.ReconciliationStatus.UNKNOWN,
                AS_OF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailRequiresChronologicalLedgerAndCopiesRefunds() {
        var cell = new PaymentMonitoringContracts.SourceCell(
                "waiting:7",
                new PaymentMonitoringContracts.ConfirmedState(
                        "501", "REFUNDED", 6, AS_OF.minusSeconds(2),
                        10000, 10000, "KRW"),
                AS_OF, AS_OF,
                PaymentMonitoringContracts.Completeness.COMPLETE,
                PaymentMonitoringContracts.ReconciliationStatus.MATCHED,
                AS_OF.minusSeconds(30));
        var prepared = new PaymentMonitoringContracts.LedgerEvent(
                "PAYMENT_PREPARED", 0, AS_OF.minusSeconds(30));
        var refunded = new PaymentMonitoringContracts.LedgerEvent(
                "REFUND_COMPLETED", 10000, AS_OF.minusSeconds(2));
        var refund = new PaymentMonitoringContracts.Refund(
                "COMPLETED", 0, 10000, AS_OF.minusSeconds(20), AS_OF.minusSeconds(2));

        var detail = new PaymentMonitoringContracts.Detail(
                cell, List.of(prepared, refunded), false, List.of(refund), false);

        assertThat(detail.refunds()).containsExactly(refund);
        assertThatThrownBy(() -> new PaymentMonitoringContracts.Detail(
                cell, List.of(refunded, prepared), false, List.of(refund), false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
