package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;

import com.miriyum.domain.payment.repository.PaymentRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorePaymentImpactQueryServiceTest {

    @Mock
    private PaymentRepository payments;

    @InjectMocks
    private StorePaymentImpactQueryService service;

    @Test
    void inspectCountsUnsettledReservationDepositsForGivenReservations() {
        given(payments.countUnsettledBySourceReferences(
                "RESERVATION_DEPOSIT", List.of("101", "102"))).willReturn(1L);

        var impact = service.inspectReservationDeposits(Set.of(102L, 101L));

        assertThat(impact.reservationCount()).isEqualTo(2L);
        assertThat(impact.unsettledCount()).isEqualTo(1L);
    }

    @Test
    void emptyReservationSetDoesNotQueryPayments() {
        var impact = service.inspectReservationDeposits(Set.of());

        assertThat(impact.unsettledCount()).isZero();
        then(payments).should(never()).countUnsettledBySourceReferences(anyString(), anyList());
    }
}
