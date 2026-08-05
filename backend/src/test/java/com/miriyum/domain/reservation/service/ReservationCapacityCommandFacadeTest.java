package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.dto.response.ReservationCapacitiesResponse;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ReservationCapacityCommandFacadeTest {

    @Mock
    private ReservationCapacityPublicationService publicationService;

    @Test
    void retriesTransientLockFailuresWithTheSameCommand() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 10);
        IdempotencyKey key = IdempotencyKey.parse(
                "550e8400-e29b-41d4-a716-446655440000"
        );
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of());
        ReservationCapacityCommandResult expected = new ReservationCapacityCommandResult(
                200,
                new ReservationCapacitiesResponse(serviceDate, 3L, List.of())
        );
        given(publicationService.replaceCapacities(
                11L,
                7L,
                serviceDate,
                key,
                request
        )).willThrow(
                new CannotAcquireLockException("first"),
                new CannotAcquireLockException("second")
        ).willReturn(expected);
        ReservationCapacityCommandFacade facade = new ReservationCapacityCommandFacade(
                publicationService,
                ignored -> 0L,
                ignored -> {
                }
        );

        // when
        ReservationCapacityCommandResult actual = facade.replace(
                11L,
                7L,
                serviceDate,
                key,
                request
        );

        // then
        assertThat(actual).isSameAs(expected);
        then(publicationService).should(times(3)).replaceCapacities(
                11L,
                7L,
                serviceDate,
                key,
                request
        );
    }

    @Test
    void translatesTheCapacityBusinessKeyConstraintToReservationConflict() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 10);
        IdempotencyKey key = IdempotencyKey.parse(
                "550e8400-e29b-41d4-a716-446655440000"
        );
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of());
        given(publicationService.replaceCapacities(
                11L,
                7L,
                serviceDate,
                key,
                request
        )).willThrow(new DataIntegrityViolationException(
                "uk_reservation_capacity_buckets_business_key"
        ));
        ReservationCapacityCommandFacade facade = new ReservationCapacityCommandFacade(
                publicationService,
                ignored -> 0L,
                ignored -> {
                }
        );

        // when & then
        assertThatThrownBy(() -> facade.replace(
                11L,
                7L,
                serviceDate,
                key,
                request
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(
                        ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT
                ));
    }
}
