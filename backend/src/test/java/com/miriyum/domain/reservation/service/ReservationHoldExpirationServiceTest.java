package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ReservationHoldExpirationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T03:00:00Z");

    @Mock
    private ReservationHoldRepository holdRepository;

    @Mock
    private ReservationHoldTransitionAuditRepository auditRepository;

    @Mock
    private ReservationHoldCommandFacade commandFacade;

    @Mock
    private ReservationDepositProcessRepository processRepository;

    @Mock
    private ReservationDepositProcessCommandFacade depositCommandFacade;

    private ReservationHoldExpirationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationHoldExpirationService(
                holdRepository,
                auditRepository,
                commandFacade,
                processRepository,
                depositCommandFacade,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("실패 후보 뒤에도 keyset cursor를 전진시키고 다음 페이지를 결정적 만료 명령으로 처리한다")
    void expireDueHoldsAdvancesKeysetPastFailures() {
        List<ReservationHoldRepository.ExpirationCandidate> firstPage = List.of(
                candidate(10L, NOW.minusSeconds(30)),
                candidate(20L, NOW.minusSeconds(20)));
        List<ReservationHoldRepository.ExpirationCandidate> secondPage = List.of(
                candidate(30L, NOW.minusSeconds(10)));
        PageRequest page = PageRequest.of(0, 2);
        given(holdRepository.findActiveExpirationCandidatesAfter(NOW, 0L, page))
                .willReturn(firstPage);
        given(holdRepository.findActiveExpirationCandidatesAfter(NOW, 20L, page))
                .willReturn(secondPage);
        given(holdRepository.findActiveExpirationCandidatesAfter(NOW, 30L, page))
                .willReturn(List.of());
        List<ReservationHoldContracts.TransitionCommand> executed = new ArrayList<>();
        given(commandFacade.transition(any())).willAnswer(invocation -> {
            ReservationHoldContracts.TransitionCommand command = invocation.getArgument(0);
            executed.add(command);
            if (command.reservationHoldId() == 10L) {
                throw new IllegalStateException("simulated candidate failure");
            }
            return null;
        });

        int completed = service.expireDueHolds(2);

        assertThat(completed).isEqualTo(2);
        assertThat(executed).extracting(
                        ReservationHoldContracts.TransitionCommand::reservationHoldId)
                .containsExactly(10L, 20L, 30L);
        assertThat(executed).allSatisfy(command -> {
            assertThat(command.targetStatus()).isEqualTo(ReservationHoldStatus.EXPIRED);
            assertThat(command.operationId())
                    .isEqualTo("reservation-hold-expire:" + command.reservationHoldId());
            assertThat(command.actorType()).isEqualTo("SYSTEM");
            assertThat(command.actorId()).isNull();
            assertThat(command.finalReservationId()).isNull();
        });
        assertThat(executed).extracting(ReservationHoldContracts.TransitionCommand::requestedAt)
                .containsExactly(
                        NOW.minusSeconds(30),
                        NOW.minusSeconds(20),
                        NOW.minusSeconds(10));
        then(holdRepository).should()
                .findActiveExpirationCandidatesAfter(NOW, 0L, page);
        then(holdRepository).should()
                .findActiveExpirationCandidatesAfter(NOW, 20L, page);
        then(holdRepository).should()
                .findActiveExpirationCandidatesAfter(NOW, 30L, page);
    }

    @Test
    @DisplayName("예약금 process가 연결된 선점은 Hold facade 대신 process-first 조정자에 위임한다")
    void expireDueHoldsRoutesLinkedHoldToDepositCoordinator() {
        PageRequest page = PageRequest.of(0, 10);
        given(holdRepository.findActiveExpirationCandidatesAfter(NOW, 0L, page))
                .willReturn(List.of(candidate(10L, NOW.minusSeconds(30))));
        given(holdRepository.findActiveExpirationCandidatesAfter(NOW, 10L, page))
                .willReturn(List.of());
        given(processRepository.findProcessIdByReservationHoldId(10L))
                .willReturn(Optional.of(99L));

        assertThat(service.expireDueHolds(10)).isEqualTo(1);

        then(depositCommandFacade).should().reconcileLinkedExpiration(99L);
        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("interrupt가 설정되면 새 후보 조회 없이 batch를 중단한다")
    void expireDueHoldsStopsBeforeQueryWhenInterrupted() {
        Thread.currentThread().interrupt();

        int completed = service.expireDueHolds(10);

        assertThat(completed).isZero();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        then(holdRepository).shouldHaveNoInteractions();
        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("batch size는 양수여야 하며 collaborator 호출 전에 검증한다")
    void expireDueHoldsRejectsNonPositiveBatchSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.expireDueHolds(0));

        then(holdRepository).shouldHaveNoInteractions();
        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("장기 체류는 전이 감사 occurredAt의 10분 경계를 포함해 현재 건수로 조회한다")
    void countLongStayingReconciliationsUsesInclusiveTenMinuteBoundary() {
        Instant boundary = NOW.minusSeconds(600);
        given(auditRepository.countCurrentReconciliationRequiredAtOrBefore(
                boundary,
                ReservationHoldStatus.RECONCILIATION_REQUIRED))
                .willReturn(3L);

        long count = service.countLongStayingReconciliations();

        assertThat(count).isEqualTo(3L);
        then(auditRepository).should()
                .countCurrentReconciliationRequiredAtOrBefore(
                        boundary,
                        ReservationHoldStatus.RECONCILIATION_REQUIRED);
        then(holdRepository).should(never())
                .findActiveExpirationCandidatesAfter(any(), any(Long.class), any());
    }

    private static ReservationHoldRepository.ExpirationCandidate candidate(
            long reservationHoldId,
            Instant expiresAt
    ) {
        return new ReservationHoldRepository.ExpirationCandidate() {
            @Override
            public Long getReservationHoldId() {
                return reservationHoldId;
            }

            @Override
            public Instant getExpiresAt() {
                return expiresAt;
            }
        };
    }
}
