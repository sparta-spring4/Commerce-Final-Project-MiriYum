package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyDraftRequest;
import com.miriyum.domain.reservation.dto.response.ReservationTimePolicyResponse;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ReservationTimePolicyCommandFacadeTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 7L;
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000"
    );
    private static final ReservationTimePolicyDraftRequest REQUEST =
            new ReservationTimePolicyDraftRequest(30, 90, 15);

    @Mock
    private ReservationService reservationService;

    private List<Long> delays;
    private ReservationTimePolicyCommandFacade facade;

    @BeforeEach
    void setUp() {
        delays = new ArrayList<>();
        facade = new ReservationTimePolicyCommandFacade(
                reservationService,
                attempt -> attempt == 1 ? 150L : 400L,
                delays::add
        );
    }

    @Test
    void transientLockFailureRetriesWholeCommandAtMostThreeTimes() {
        ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> success =
                new ReservationTimePolicyCommandResult<>(
                        200,
                        new ReservationTimePolicyResponse(
                                "7", 1L, 30, 90, 15,
                                ReservationTimePolicyStatus.DRAFT, null
                        )
                );
        given(reservationService.createTimePolicyDraft(
                OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .willThrow(new CannotAcquireLockException("first"))
                .willThrow(new CannotAcquireLockException("second"))
                .willReturn(success);

        assertThat(facade.createDraft(OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .isSameAs(success);
        assertThat(delays).containsExactly(150L, 400L);
        then(reservationService).should(times(3)).createTimePolicyDraft(
                OPERATOR_ID, STORE_ID, KEY, REQUEST
        );
    }

    @Test
    void exhaustedTransientConflictReturnsCommon008() {
        given(reservationService.createTimePolicyDraft(
                OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .willThrow(new CannotAcquireLockException("always"));

        assertThatThrownBy(() ->
                facade.createDraft(OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));
        assertThat(delays).containsExactly(150L, 400L);
        then(reservationService).should(times(3)).createTimePolicyDraft(
                OPERATOR_ID, STORE_ID, KEY, REQUEST
        );
    }

    @Test
    void policyConstraintConflictReturnsReservation010WithoutRetry() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "constraint failed",
                new IllegalStateException("uk_reservation_time_policy_active_store")
        );
        given(reservationService.createTimePolicyDraft(
                OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .willThrow(failure);

        assertThatThrownBy(() ->
                facade.createDraft(OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.TIME_POLICY_CONFLICT));
        assertThat(delays).isEmpty();
        then(reservationService).should().createTimePolicyDraft(
                OPERATOR_ID, STORE_ID, KEY, REQUEST
        );
    }

    @Test
    void unrelatedIntegrityFailureIsNotHiddenOrRetried() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unrelated");
        given(reservationService.createTimePolicyDraft(
                OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .willThrow(failure);

        assertThatThrownBy(() ->
                facade.createDraft(OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .isSameAs(failure);
        assertThat(delays).isEmpty();
    }

    @Test
    void auditIntegrityFailureIsNotMisreportedAsPolicyConflict() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "constraint failed",
                new IllegalStateException(
                        "ck_reservation_time_policy_audit_conflict"
                )
        );
        given(reservationService.createTimePolicyDraft(
                OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .willThrow(failure);

        assertThatThrownBy(() ->
                facade.createDraft(OPERATOR_ID, STORE_ID, KEY, REQUEST))
                .isSameAs(failure);
        assertThat(delays).isEmpty();
    }
}
