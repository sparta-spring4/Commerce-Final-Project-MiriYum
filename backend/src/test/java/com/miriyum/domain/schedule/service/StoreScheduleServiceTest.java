package com.miriyum.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.miriyum.domain.store.service.StoreScheduleAuthority;
import com.miriyum.domain.store.service.StoreScheduledActivationDecision;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.schedule.dto.storeoperator.DailyOperatingScheduleRequest;
import com.miriyum.domain.schedule.dto.storeoperator.DailyReservationSlotsRequest;
import com.miriyum.domain.schedule.dto.storeoperator.OperatingHoursResponse;
import com.miriyum.domain.schedule.dto.storeoperator.ReservationTimeSlotsResponse;
import com.miriyum.domain.schedule.dto.storeoperator.SchedulePublicationRequest;
import com.miriyum.domain.schedule.dto.storeoperator.SchedulePublicationCancellationRequest;
import com.miriyum.domain.schedule.dto.storeoperator.TimeRangeRequest;
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowResult;
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowStatus;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyOperatingHoursRequest;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyReservationTimeSlotsRequest;
import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.schedule.entity.StoreScheduleAuditEvent;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ScheduleAuditAction;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.schedule.model.PublicationMode;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.WeeklyInterval;
import com.miriyum.domain.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.ReservationScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.StoreScheduleAuditEventRepository;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StoreScheduleServiceTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 7L;
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private StoreService storeService;

    @Mock
    private StoreScheduleStateRepository stateRepository;

    @Mock
    private OperatingScheduleVersionRepository operatingRepository;

    @Mock
    private ReservationScheduleVersionRepository reservationRepository;

    @Mock
    private StoreScheduleAuditEventRepository auditRepository;

    @Mock
    private WeeklySchedulePolicy schedulePolicy;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    private ObjectMapper objectMapper;
    private StoreScheduleService scheduleService;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T03:00:00Z"),
            ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scheduleService = new StoreScheduleService(
                storeService,
                stateRepository,
                operatingRepository,
                reservationRepository,
                auditRepository,
                schedulePolicy,
                idempotencyExecutor,
                objectMapper,
                FIXED_CLOCK);
    }

    @Test
    void operatingPutCreatesDraftWithoutChangingActivePointers() {
        StoreScheduleState state = state();
        ReflectionTestUtils.setField(
                state,
                "activeOperatingScheduleVersionId",
                20L);
        state.activateReservation(31L);
        WeeklyOperatingHoursRequest request = operatingRequest();
        List<WeeklyInterval> intervals = operatingIntervals();
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID,
                STORE_ID))
                .willReturn(new StoreScheduleAuthority(
                        STORE_ID,
                        "Asia/Seoul"));
        given(schedulePolicy.validateOperating(request)).willReturn(intervals);
        given(operatingRepository.saveAndFlush(any())).willAnswer(invocation -> {
            OperatingScheduleVersion version = invocation.getArgument(0);
            ReflectionTestUtils.setField(version, "id", 21L);
            return version;
        });
        runBusinessWorkOnExecute();

        ScheduleCommandResult<OperatingHoursResponse> result =
                scheduleService.createOperatingDraft(
                        OPERATOR_ID,
                        STORE_ID,
                        IdempotencyKey.parse(KEY),
                        request);

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data().version()).isEqualTo(1);
        assertThat(result.data().status()).isEqualTo(ScheduleVersionStatus.DRAFT);
        assertThat(result.data().timeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(state.getActiveOperatingScheduleVersionId()).isEqualTo(20L);
        assertThat(state.getActiveReservationScheduleVersionId()).isEqualTo(31L);
        then(auditRepository).should().save(any(StoreScheduleAuditEvent.class));
    }

    @Test
    void reservationPutCreatesDraftWithoutChangingActivePointer() {
        StoreScheduleState state = state();
        ReflectionTestUtils.setField(state, "activeOperatingScheduleVersionId", 21L);
        ReflectionTestUtils.setField(state, "activeReservationScheduleVersionId", 30L);
        OperatingScheduleVersion operating = OperatingScheduleVersion.create(
                STORE_ID,
                4L,
                operatingIntervals());
        ReflectionTestUtils.setField(operating, "id", 21L);
        WeeklyReservationTimeSlotsRequest request = reservationRequest();
        List<WeeklyInterval> reservationIntervals = reservationIntervals();
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID,
                STORE_ID))
                .willReturn(new StoreScheduleAuthority(
                        STORE_ID,
                        "Asia/Seoul"));
        given(operatingRepository.findById(21L)).willReturn(Optional.of(operating));
        given(schedulePolicy.validateReservation(request, operatingIntervals()))
                .willReturn(reservationIntervals);
        AtomicReference<ReservationScheduleVersion> savedVersion =
                new AtomicReference<>();
        given(reservationRepository.saveAndFlush(any())).willAnswer(invocation -> {
            ReservationScheduleVersion version = invocation.getArgument(0);
            ReflectionTestUtils.setField(version, "id", 31L);
            savedVersion.set(version);
            return version;
        });
        runBusinessWorkOnExecute();

        ScheduleCommandResult<ReservationTimeSlotsResponse> result =
                scheduleService.createReservationDraft(
                        OPERATOR_ID,
                        STORE_ID,
                        IdempotencyKey.parse(KEY),
                        request);

        assertThat(result.data().version()).isEqualTo(1);
        assertThat(result.data().status()).isEqualTo(ScheduleVersionStatus.DRAFT);
        assertThat(state.getActiveReservationScheduleVersionId()).isEqualTo(30L);
        assertThat(savedVersion.get().getValidatedOperatingVersionId()).isEqualTo(21L);
        then(auditRepository).should().save(any(StoreScheduleAuditEvent.class));
    }

    @Test
    void immediateOperatingPublicationActivatesDraftAndRetiresPreviousVersion() {
        StoreScheduleState state = state();
        state.activateOperating(21L);
        state.activateReservation(31L);
        OperatingScheduleVersion previous = OperatingScheduleVersion.create(
                STORE_ID,
                1L,
                operatingIntervals());
        ReflectionTestUtils.setField(previous, "id", 21L);
        OperatingScheduleVersion draft = OperatingScheduleVersion.createDraft(
                STORE_ID,
                2L,
                "Asia/Seoul",
                operatingIntervals());
        ReflectionTestUtils.setField(draft, "id", 22L);
        ReservationScheduleVersion previousReservation =
                ReservationScheduleVersion.create(
                        STORE_ID,
                        1L,
                        21L,
                        reservationIntervals());
        ReflectionTestUtils.setField(previousReservation, "id", 31L);
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID,
                STORE_ID))
                .willReturn(new StoreScheduleAuthority(
                        STORE_ID,
                        "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(operatingRepository.findByStoreIdAndVersionNumber(
                STORE_ID,
                2L))
                .willReturn(Optional.of(draft));
        given(operatingRepository.findById(21L))
                .willReturn(Optional.of(previous));
        given(reservationRepository.findById(31L))
                .willReturn(Optional.of(previousReservation));
        runBusinessWorkOnExecute();

        ScheduleCommandResult<OperatingHoursResponse> result =
                scheduleService.publishOperating(
                        OPERATOR_ID,
                        STORE_ID,
                        2L,
                        IdempotencyKey.parse(KEY),
                        new SchedulePublicationRequest(
                                PublicationMode.IMMEDIATE,
                                null,
                                "여름 영업시간"));

        assertThat(result.data().status()).isEqualTo(ScheduleVersionStatus.ACTIVE);
        assertThat(previous.getStatus()).isEqualTo(ScheduleVersionStatus.RETIRED);
        assertThat(state.getActiveOperatingScheduleVersionId()).isEqualTo(22L);
        assertThat(state.getActiveReservationScheduleVersionId()).isNull();
        assertThat(previousReservation.getStatus())
                .isEqualTo(ScheduleVersionStatus.RETIRED);
        ArgumentCaptor<StoreScheduleAuditEvent> auditCaptor =
                ArgumentCaptor.forClass(StoreScheduleAuditEvent.class);
        then(auditRepository).should(times(2)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
                .filteredOn(event -> event.getAction()
                        == ScheduleAuditAction
                        .RESERVATION_RETIRED_BY_OPERATING_CHANGE)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getTargetVersion()).isEqualTo(1L);
                    assertThat(event.getPreviousActiveVersion()).isEqualTo(1L);
                    assertThat(event.getNewActiveVersion()).isNull();
                    assertThat(event.getPreviousStatus())
                            .isEqualTo(ScheduleVersionStatus.ACTIVE);
                    assertThat(event.getNewStatus())
                            .isEqualTo(ScheduleVersionStatus.RETIRED);
                    assertThat(event.getChangeReason())
                            .isEqualTo("여름 영업시간");
                    assertThat(event.getRequestId()).isEqualTo(KEY);
                });
    }

    @Test
    void scheduledOperatingPublicationKeepsCurrentPointer() {
        StoreScheduleState state = state();
        OperatingScheduleVersion draft = OperatingScheduleVersion.createDraft(
                STORE_ID, 2L, "Asia/Seoul", operatingIntervals());
        ReflectionTestUtils.setField(draft, "id", 22L);
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(operatingRepository.findByStoreIdAndVersionNumber(STORE_ID, 2L))
                .willReturn(Optional.of(draft));
        runBusinessWorkOnExecute();

        ScheduleCommandResult<OperatingHoursResponse> result =
                scheduleService.publishOperating(
                        OPERATOR_ID,
                        STORE_ID,
                        2L,
                        IdempotencyKey.parse(KEY),
                        new SchedulePublicationRequest(
                                PublicationMode.SCHEDULED,
                                OffsetDateTime.parse("2026-08-01T12:00:00+09:00"),
                                "여름 영업시간"));

        assertThat(result.data().status())
                .isEqualTo(ScheduleVersionStatus.SCHEDULED);
        assertThat(result.data().effectiveAt())
                .isEqualTo(Instant.parse("2026-08-01T03:00:00Z"));
        assertThat(state.getActiveOperatingScheduleVersionId()).isNull();
    }

    @Test
    void cancellationReturnsScheduledOperatingVersionToDraft() {
        StoreScheduleState state = state();
        OperatingScheduleVersion scheduled = OperatingScheduleVersion.createDraft(
                STORE_ID, 2L, "Asia/Seoul", operatingIntervals());
        ReflectionTestUtils.setField(scheduled, "id", 22L);
        scheduled.schedule(
                Instant.parse("2026-08-01T03:00:00Z"),
                "여름 영업시간");
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(operatingRepository.findByStoreIdAndVersionNumber(STORE_ID, 2L))
                .willReturn(Optional.of(scheduled));
        runBusinessWorkOnExecute();

        ScheduleCommandResult<OperatingHoursResponse> result =
                scheduleService.cancelOperatingPublication(
                        OPERATOR_ID,
                        STORE_ID,
                        2L,
                        IdempotencyKey.parse(KEY),
                        new SchedulePublicationCancellationRequest(
                                "오픈 일정 변경"));

        assertThat(result.data().status()).isEqualTo(ScheduleVersionStatus.DRAFT);
        assertThat(result.data().effectiveAt()).isNull();
        assertThat(result.data().changeReason()).isNull();
    }

    @Test
    void operatingPublicationCannotBeCancelledAtOrAfterItsEffectiveTime() {
        StoreScheduleState state = state();
        OperatingScheduleVersion scheduled = OperatingScheduleVersion.createDraft(
                STORE_ID, 2L, "Asia/Seoul", operatingIntervals());
        scheduled.schedule(FIXED_CLOCK.instant(), "여름 영업시간");
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(operatingRepository.findByStoreIdAndVersionNumber(STORE_ID, 2L))
                .willReturn(Optional.of(scheduled));
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> scheduleService.cancelOperatingPublication(
                OPERATOR_ID,
                STORE_ID,
                2L,
                IdempotencyKey.parse(KEY),
                new SchedulePublicationCancellationRequest("효력 시각 경과")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);
        assertThat(scheduled.getStatus()).isEqualTo(ScheduleVersionStatus.SCHEDULED);
    }

    @Test
    void reservationPublicationCannotBeCancelledAtOrAfterItsEffectiveTime() {
        StoreScheduleState state = state();
        ReservationScheduleVersion scheduled =
                ReservationScheduleVersion.createDraft(
                        STORE_ID,
                        2L,
                        21L,
                        "Asia/Seoul",
                        reservationIntervals());
        scheduled.schedule(FIXED_CLOCK.instant(), "여름 예약시간");
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(reservationRepository.findByStoreIdAndVersionNumber(
                STORE_ID, 2L))
                .willReturn(Optional.of(scheduled));
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> scheduleService.cancelReservationPublication(
                OPERATOR_ID,
                STORE_ID,
                2L,
                IdempotencyKey.parse(KEY),
                new SchedulePublicationCancellationRequest("효력 시각 경과")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);
        assertThat(scheduled.getStatus()).isEqualTo(ScheduleVersionStatus.SCHEDULED);
    }

    @Test
    void permanentlyInvalidStoreMarksDueOperatingActivationFailed() {
        StoreScheduleState state = state();
        OperatingScheduleVersion scheduled = OperatingScheduleVersion.createDraft(
                STORE_ID, 2L, "Asia/Seoul", operatingIntervals());
        ReflectionTestUtils.setField(scheduled, "id", 22L);
        scheduled.schedule(FIXED_CLOCK.instant(), "여름 영업시간");
        given(operatingRepository.findStoreIdById(22L))
                .willReturn(Optional.of(STORE_ID));
        given(storeService.inspectScheduledActivation(STORE_ID))
                .willReturn(new StoreScheduledActivationDecision(
                        STORE_ID, "Asia/Seoul", false));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(operatingRepository.findForUpdateById(22L))
                .willReturn(Optional.of(scheduled));
        given(operatingRepository
                .findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        STORE_ID,
                        ScheduleVersionStatus.SCHEDULED,
                        FIXED_CLOCK.instant()))
                .willReturn(Optional.of(scheduled));

        scheduleService.activateDueOperating(22L);

        assertThat(scheduled.getStatus())
                .isEqualTo(ScheduleVersionStatus.ACTIVATION_FAILED);
        then(auditRepository).should().save(any(StoreScheduleAuditEvent.class));
    }

    @Test
    void scheduledOperatingActivationAuditsRetiredReservationVersion() {
        StoreScheduleState state = state();
        state.activateOperating(21L);
        state.activateReservation(31L);
        OperatingScheduleVersion previous = OperatingScheduleVersion.create(
                STORE_ID,
                1L,
                operatingIntervals());
        ReflectionTestUtils.setField(previous, "id", 21L);
        OperatingScheduleVersion scheduled = OperatingScheduleVersion.createDraft(
                STORE_ID, 2L, "Asia/Seoul", operatingIntervals());
        ReflectionTestUtils.setField(scheduled, "id", 22L);
        scheduled.schedule(FIXED_CLOCK.instant(), "여름 영업시간");
        ReservationScheduleVersion previousReservation =
                ReservationScheduleVersion.create(
                        STORE_ID,
                        1L,
                        21L,
                        reservationIntervals());
        ReflectionTestUtils.setField(previousReservation, "id", 31L);
        given(operatingRepository.findStoreIdById(22L))
                .willReturn(Optional.of(STORE_ID));
        given(storeService.inspectScheduledActivation(STORE_ID))
                .willReturn(new StoreScheduledActivationDecision(
                        STORE_ID, "Asia/Seoul", true));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(operatingRepository.findForUpdateById(22L))
                .willReturn(Optional.of(scheduled));
        given(operatingRepository
                .findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        STORE_ID,
                        ScheduleVersionStatus.SCHEDULED,
                        FIXED_CLOCK.instant()))
                .willReturn(Optional.of(scheduled));
        given(operatingRepository.findById(21L))
                .willReturn(Optional.of(previous));
        given(reservationRepository.findById(31L))
                .willReturn(Optional.of(previousReservation));

        scheduleService.activateDueOperating(22L);

        ArgumentCaptor<StoreScheduleAuditEvent> auditCaptor =
                ArgumentCaptor.forClass(StoreScheduleAuditEvent.class);
        then(auditRepository).should(times(2)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
                .filteredOn(event -> event.getAction()
                        == ScheduleAuditAction
                        .RESERVATION_RETIRED_BY_OPERATING_CHANGE)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getTargetVersion()).isEqualTo(1L);
                    assertThat(event.getPreviousActiveVersion()).isEqualTo(1L);
                    assertThat(event.getNewActiveVersion()).isNull();
                    assertThat(event.getPreviousStatus())
                            .isEqualTo(ScheduleVersionStatus.ACTIVE);
                    assertThat(event.getNewStatus())
                            .isEqualTo(ScheduleVersionStatus.RETIRED);
                    assertThat(event.getChangeReason())
                            .isEqualTo("여름 영업시간");
                    assertThat(event.getRequestId())
                            .isEqualTo("scheduled-operating-22");
                });
    }

    @Test
    void immediateReservationPublicationRequiresItsValidatedOperatingVersion() {
        StoreScheduleState state = state();
        state.activateOperating(21L);
        ReservationScheduleVersion draft =
                ReservationScheduleVersion.createDraft(
                        STORE_ID,
                        2L,
                        21L,
                        "Asia/Seoul",
                        reservationIntervals());
        ReflectionTestUtils.setField(draft, "id", 32L);
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(reservationRepository.findByStoreIdAndVersionNumber(
                STORE_ID, 2L))
                .willReturn(Optional.of(draft));
        runBusinessWorkOnExecute();

        ScheduleCommandResult<ReservationTimeSlotsResponse> result =
                scheduleService.publishReservation(
                        OPERATOR_ID,
                        STORE_ID,
                        2L,
                        IdempotencyKey.parse(KEY),
                        new SchedulePublicationRequest(
                                PublicationMode.IMMEDIATE,
                                null,
                                "예약 접수 시간 확대"));

        assertThat(result.data().status())
                .isEqualTo(ScheduleVersionStatus.ACTIVE);
        assertThat(state.getActiveReservationScheduleVersionId())
                .isEqualTo(32L);
    }

    @Test
    void reservationPublicationWithoutOperatingScheduleReturnsStore006() {
        StoreScheduleState state = state();
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> scheduleService.replaceReservationTimeSlots(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(KEY),
                reservationRequest()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.SCHEDULE_CONFLICT);
    }

    @Test
    void successfulReplayDoesNotRecheckMutablePublicationState() {
        given(idempotencyExecutor.execute(any(), any()))
                .willReturn(new IdempotentOutcome(
                        true,
                        200,
                        "SUCCESS",
                        "STORE_SCHEDULE",
                        "7:OPERATING:1",
                        objectMapper.valueToTree(
                                new OperatingHoursResponse(1, List.of()))));

        ScheduleCommandResult<OperatingHoursResponse> result =
                scheduleService.replaceOperatingHours(
                        OPERATOR_ID,
                        STORE_ID,
                        IdempotencyKey.parse(KEY),
                        operatingRequest());

        assertThat(result.data().version()).isEqualTo(1);
        then(storeService).should()
                .requireManagementOwnership(OPERATOR_ID, STORE_ID);
        then(storeService).should(never())
                .requireSchedulePublicationAuthority(anyLong(), anyLong());
    }

    @Test
    void newPublicationChecksMutableAuthorityInsideIdempotentWork() {
        willThrow(new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT))
                .given(storeService)
                .requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID);
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> scheduleService.replaceOperatingHours(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(KEY),
                operatingRequest()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);
        then(idempotencyExecutor).should().execute(any(), any());
    }

    @Test
    void temporarilyClosedStoreCanSaveScheduleDraft() {
        StoreScheduleState state = state();
        WeeklyOperatingHoursRequest request = operatingRequest();
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID,
                STORE_ID))
                .willReturn(new StoreScheduleAuthority(
                        STORE_ID,
                        "Asia/Seoul"));
        given(schedulePolicy.validateOperating(request))
                .willReturn(operatingIntervals());
        given(operatingRepository.saveAndFlush(any())).willAnswer(invocation -> {
            OperatingScheduleVersion version = invocation.getArgument(0);
            ReflectionTestUtils.setField(version, "id", 21L);
            return version;
        });
        runBusinessWorkOnExecute();

        ScheduleCommandResult<OperatingHoursResponse> result =
                scheduleService.replaceOperatingHours(
                        OPERATOR_ID,
                        STORE_ID,
                        IdempotencyKey.parse(KEY),
                        request);

        assertThat(result.data().version()).isEqualTo(1);
    }

    @Test
    void draftsUseDistinctCommandTypesAndCanonicalFingerprints() {
        AtomicReference<IdempotencyCommand> command = new AtomicReference<>();
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            command.set(invocation.getArgument(0));
            return new IdempotentOutcome(
                    false,
                    200,
                    "SUCCESS",
                    "STORE_SCHEDULE",
                    "7:OPERATING:1",
                    objectMapper.valueToTree(new OperatingHoursResponse(1, List.of())));
        });
        WeeklyOperatingHoursRequest request = operatingRequest();

        scheduleService.replaceOperatingHours(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(KEY),
                request);

        assertThat(command.get().commandType())
                .isEqualTo("STORE_OPERATING_HOURS_DRAFT_CREATE");
        assertThat(command.get().requestFingerprint())
                .isEqualTo(StoreScheduleFingerprint.forOperating(STORE_ID, request));
    }

    @Test
    void unrelatedPersistenceFailureIsNotHidden() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unexpected constraint");
        StoreScheduleState state = state();
        WeeklyOperatingHoursRequest request = operatingRequest();
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(storeService.requireSchedulePublicationAuthority(
                OPERATOR_ID,
                STORE_ID))
                .willReturn(new StoreScheduleAuthority(
                        STORE_ID,
                        "Asia/Seoul"));
        given(schedulePolicy.validateOperating(request))
                .willReturn(operatingIntervals());
        given(operatingRepository.saveAndFlush(any())).willThrow(failure);
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> scheduleService.replaceOperatingHours(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(KEY),
                request))
                .isSameAs(failure);
    }

    @Test
    void emptyReservationWindowRequestReturnsEmptyWithoutRepositoryCalls() {
        assertThat(scheduleService.resolveReservationWindows(
                List.of(),
                LocalDate.of(2026, 8, 3),
                LocalTime.NOON)).isEmpty();

        then(stateRepository).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    void reservationWindowsPreserveInputOrderAndDuplicatesAndFailClosed() {
        StoreScheduleState firstState = StoreScheduleState.initialize(7L);
        firstState.activateReservation(31L);
        StoreScheduleState secondState = StoreScheduleState.initialize(8L);
        secondState.activateReservation(32L);
        StoreScheduleState staleState = StoreScheduleState.initialize(9L);
        staleState.activateReservation(39L);
        ReservationScheduleVersion first = activeReservationVersion(
                7L,
                31L,
                "Asia/Seoul",
                new WeeklyInterval(
                        DayOfWeek.MONDAY,
                        LocalTime.of(19, 0),
                        LocalTime.of(1, 0),
                        true,
                        ScheduleIntervalKind.RESERVATION_SLOT,
                        1140,
                        1500));
        ReservationScheduleVersion second = activeReservationVersion(
                8L,
                32L,
                "Asia/Seoul",
                new WeeklyInterval(
                        DayOfWeek.SUNDAY,
                        LocalTime.of(23, 0),
                        LocalTime.of(1, 0),
                        true,
                        ScheduleIntervalKind.RESERVATION_SLOT,
                        10020,
                        10140));
        given(stateRepository.findAllByStoreIdIn(List.of(8L, 7L, 9L)))
                .willReturn(List.of(firstState, secondState, staleState));
        given(reservationRepository.findActiveByIdsWithEntries(
                List.of(31L, 32L, 39L),
                ScheduleVersionStatus.ACTIVE))
                .willReturn(List.of(first, second));

        List<StoreReservationWindowResult> results =
                scheduleService.resolveReservationWindows(
                        List.of(8L, 7L, 9L, 8L),
                        LocalDate.of(2026, 8, 3),
                        LocalTime.of(0, 30));

        assertThat(results).extracting(StoreReservationWindowResult::storeId)
                .containsExactly(8L, 7L, 9L, 8L);
        assertThat(results).extracting(StoreReservationWindowResult::status)
                .containsExactly(
                        StoreReservationWindowStatus.ACCEPTING,
                        StoreReservationWindowStatus.NOT_ACCEPTING,
                        StoreReservationWindowStatus.NOT_ACCEPTING,
                        StoreReservationWindowStatus.ACCEPTING);
        assertThat(results.getFirst().windowStartAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 2, 23, 0));
        assertThat(results.getFirst().windowEndAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 3, 1, 0));
        assertThat(results.getLast()).isEqualTo(results.getFirst());
        then(stateRepository).should(times(1))
                .findAllByStoreIdIn(List.of(8L, 7L, 9L));
        then(reservationRepository).should(times(1))
                .findActiveByIdsWithEntries(
                        List.of(31L, 32L, 39L),
                        ScheduleVersionStatus.ACTIVE);
    }

    @Test
    void reservationWindowUsesInclusiveStartAndExclusiveEnd() {
        StoreScheduleState state = StoreScheduleState.initialize(STORE_ID);
        state.activateReservation(31L);
        ReservationScheduleVersion version = activeReservationVersion(
                STORE_ID,
                31L,
                "Asia/Seoul",
                reservationIntervals().getFirst());
        given(stateRepository.findAllByStoreIdIn(List.of(STORE_ID)))
                .willReturn(List.of(state));
        given(reservationRepository.findActiveByIdsWithEntries(
                List.of(31L), ScheduleVersionStatus.ACTIVE))
                .willReturn(List.of(version));

        StoreReservationWindowResult atStart = scheduleService
                .resolveReservationWindows(
                        List.of(STORE_ID),
                        LocalDate.of(2026, 8, 3),
                        LocalTime.of(19, 0))
                .getFirst();
        StoreReservationWindowResult atEnd = scheduleService
                .resolveReservationWindows(
                        List.of(STORE_ID),
                        LocalDate.of(2026, 8, 4),
                        LocalTime.of(1, 0))
                .getFirst();

        assertThat(atStart.status()).isEqualTo(StoreReservationWindowStatus.ACCEPTING);
        assertThat(atStart.windowEndAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 4, 1, 0));
        assertThat(atEnd.status())
                .isEqualTo(StoreReservationWindowStatus.NOT_ACCEPTING);
    }

    @Test
    void reservationWindowRequestRejectsInvalidArgumentsBeforeQuerying() {
        assertThatThrownBy(() -> scheduleService.resolveReservationWindows(
                null,
                LocalDate.of(2026, 8, 3),
                LocalTime.NOON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scheduleService.resolveReservationWindows(
                List.of(0L),
                LocalDate.of(2026, 8, 3),
                LocalTime.NOON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scheduleService.resolveReservationWindows(
                Arrays.asList(STORE_ID, null),
                LocalDate.of(2026, 8, 3),
                LocalTime.NOON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scheduleService.resolveReservationWindows(
                List.of(STORE_ID),
                null,
                LocalTime.NOON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scheduleService.resolveReservationWindows(
                List.of(STORE_ID),
                LocalDate.of(2026, 8, 3),
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scheduleService.resolveReservationWindows(
                List.of(STORE_ID),
                LocalDate.of(2026, 8, 3),
                LocalTime.of(12, 0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scheduleService.resolveReservationWindows(
                List.of(STORE_ID),
                LocalDate.of(2026, 8, 3),
                LocalTime.NOON.plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);

        then(stateRepository).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
    }

    private void runBusinessWorkOnExecute() {
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(
                    false,
                    result.httpStatus(),
                    result.responseCode(),
                    result.resourceType(),
                    result.resourceId(),
                    objectMapper.valueToTree(result.data()));
        });
    }

    private StoreScheduleState state() {
        return StoreScheduleState.initialize(STORE_ID);
    }

    private ReservationScheduleVersion activeReservationVersion(
            long storeId,
            long versionId,
            String timeZoneId,
            WeeklyInterval interval
    ) {
        ReservationScheduleVersion version = ReservationScheduleVersion.createDraft(
                storeId,
                1L,
                21L,
                timeZoneId,
                List.of(interval));
        version.activate(FIXED_CLOCK.instant(), "test active reservation window");
        ReflectionTestUtils.setField(version, "id", versionId);
        return version;
    }

    private WeeklyOperatingHoursRequest operatingRequest() {
        return new WeeklyOperatingHoursRequest(Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyOperatingScheduleRequest(
                        day,
                        day == DayOfWeek.MONDAY
                                ? List.of(new TimeRangeRequest(
                                        LocalTime.of(18, 0),
                                        LocalTime.of(2, 0)))
                                : List.of(),
                        List.of()))
                .toList());
    }

    private WeeklyReservationTimeSlotsRequest reservationRequest() {
        return new WeeklyReservationTimeSlotsRequest(
                Arrays.stream(DayOfWeek.values())
                        .map(day -> new DailyReservationSlotsRequest(
                                day,
                                day == DayOfWeek.MONDAY
                                        ? List.of(new TimeRangeRequest(
                                                LocalTime.of(19, 0),
                                                LocalTime.of(1, 0)))
                                        : List.of()))
                        .toList());
    }

    private List<WeeklyInterval> operatingIntervals() {
        return List.of(new WeeklyInterval(
                DayOfWeek.MONDAY,
                LocalTime.of(18, 0),
                LocalTime.of(2, 0),
                true,
                ScheduleIntervalKind.BUSINESS_HOURS,
                1080,
                1560));
    }

    private List<WeeklyInterval> reservationIntervals() {
        return List.of(new WeeklyInterval(
                DayOfWeek.MONDAY,
                LocalTime.of(19, 0),
                LocalTime.of(1, 0),
                true,
                ScheduleIntervalKind.RESERVATION_SLOT,
                1140,
                1500));
    }
}
