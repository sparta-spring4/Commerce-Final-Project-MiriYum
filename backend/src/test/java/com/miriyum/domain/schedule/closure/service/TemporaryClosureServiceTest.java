package com.miriyum.domain.schedule.closure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.lenient;
import com.miriyum.domain.schedule.closure.dto.storeoperator.TemporaryClosureCancellationRequest;
import com.miriyum.domain.schedule.closure.dto.storeoperator.TemporaryClosureCreateRequest;
import com.miriyum.domain.schedule.closure.dto.storeoperator.TemporaryClosureEndAtRequest;
import com.miriyum.domain.schedule.closure.entity.StoreClosureAuditEvent;
import com.miriyum.domain.schedule.closure.entity.TemporaryClosure;
import com.miriyum.domain.schedule.closure.model.StoreClosureActorType;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureReason;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureStatus;
import com.miriyum.domain.schedule.closure.repository.*;
import com.miriyum.domain.store.service.*;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ConflictCheckStatus;
import com.miriyum.domain.schedule.model.ScheduleAuditOutcome;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.global.idempotency.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class TemporaryClosureServiceTest {
    @Mock StoreService stores; @Mock StoreScheduleStateRepository states;
    @Mock RegularClosureVersionRepository regular; @Mock TemporaryClosureRepository temporary;
    @Mock StoreClosureAuditEventRepository audit; @Mock IdempotencyExecutor idempotency;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-03T03:00:00Z"), ZoneOffset.UTC);
    private ObjectMapper mapper; private TemporaryClosureService service;

    @BeforeEach void setUp() {
        mapper = new ObjectMapper();
        service = new TemporaryClosureService(stores, states, regular, temporary, audit, idempotency, mapper, clock);
    }

    @Test void createsClosureWithCanonicalInstantsAndAudit() {
        given(stores.requireSchedulePublicationAuthority(11, 7)).willReturn(new StoreScheduleAuthority(7, "Asia/Seoul"));
        given(states.findForUpdateByStoreId(7)).willReturn(Optional.of(StoreScheduleState.initialize(7)));
        given(temporary.saveAndFlush(any())).willAnswer(invocation -> {
            TemporaryClosure closure = invocation.getArgument(0); ReflectionTestUtils.setField(closure, "id", 3L); return closure;
        });
        given(idempotency.execute(any(), any())).willAnswer(invocation -> {
            Supplier<com.miriyum.global.idempotency.BusinessResult<?>> work = invocation.getArgument(1);
            var result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(), result.resourceType(),
                    result.resourceId(), mapper.valueToTree(result.data()));
        });

        var result = service.create(11, 7, IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                new TemporaryClosureCreateRequest(OffsetDateTime.parse("2026-08-03T18:00:00+09:00"),
                        OffsetDateTime.parse("2026-08-03T19:00:00+09:00"), TemporaryClosureReason.OTHER, null));

        assertThat(result.data().startAt()).isEqualTo(Instant.parse("2026-08-03T09:00:00Z"));
        assertThat(result.data().status()).isEqualTo(TemporaryClosureStatus.SCHEDULED);
        ArgumentCaptor<StoreClosureAuditEvent> captor =
                ArgumentCaptor.forClass(StoreClosureAuditEvent.class);
        then(audit).should().save(captor.capture());
        assertThat(captor.getValue().getActorType()).isEqualTo(StoreClosureActorType.STORE_OPERATOR);
        assertThat(captor.getValue().getActorId()).isEqualTo(11L);
        assertThat(captor.getValue().getAction()).isEqualTo("CREATED");
        assertTemporaryAudit(captor.getValue(),
                Instant.parse("2026-08-03T09:00:00Z"), null,
                Instant.parse("2026-08-03T10:00:00Z"), TemporaryClosureReason.OTHER);
        assertNotEvaluated(captor.getValue());
    }

    @Test void createsClosureWhenAnotherTemporaryClosureOverlaps() {
        TemporaryClosure existing = TemporaryClosure.create(7,
                Instant.parse("2026-08-03T08:30:00Z"), Instant.parse("2026-08-03T09:30:00Z"),
                "Asia/Seoul", TemporaryClosureReason.MAINTENANCE, "기존 휴무");
        given(stores.requireSchedulePublicationAuthority(11, 7))
                .willReturn(new StoreScheduleAuthority(7, "Asia/Seoul"));
        given(states.findForUpdateByStoreId(7))
                .willReturn(Optional.of(StoreScheduleState.initialize(7)));
        lenient().when(temporary.findOverlapping(any(), any(), any())).thenReturn(List.of(existing));
        given(temporary.saveAndFlush(any())).willAnswer(invocation -> {
            TemporaryClosure closure = invocation.getArgument(0);
            ReflectionTestUtils.setField(closure, "id", 4L);
            return closure;
        });
        given(idempotency.execute(any(), any())).willAnswer(invocation -> {
            Supplier<com.miriyum.global.idempotency.BusinessResult<?>> work = invocation.getArgument(1);
            var result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(), result.resourceType(),
                    result.resourceId(), mapper.valueToTree(result.data()));
        });

        var result = service.create(11, 7, IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001"),
                new TemporaryClosureCreateRequest(OffsetDateTime.parse("2026-08-03T18:00:00+09:00"),
                        OffsetDateTime.parse("2026-08-03T19:00:00+09:00"), TemporaryClosureReason.OTHER, null));

        assertThat(result.data().status()).isEqualTo(TemporaryClosureStatus.SCHEDULED);
    }

    @Test void changesEndWhenTheResultOverlapsAnotherTemporaryClosure() {
        TemporaryClosure target = TemporaryClosure.create(7,
                Instant.parse("2026-08-03T04:00:00Z"), Instant.parse("2026-08-03T05:00:00Z"),
                "Asia/Seoul", TemporaryClosureReason.OTHER, null);
        ReflectionTestUtils.setField(target, "id", 3L);
        TemporaryClosure existing = TemporaryClosure.create(7,
                Instant.parse("2026-08-03T04:30:00Z"), Instant.parse("2026-08-03T07:00:00Z"),
                "Asia/Seoul", TemporaryClosureReason.MAINTENANCE, null);
        ReflectionTestUtils.setField(existing, "id", 4L);
        given(stores.requireSchedulePublicationAuthority(11, 7))
                .willReturn(new StoreScheduleAuthority(7, "Asia/Seoul"));
        given(states.findForUpdateByStoreId(7))
                .willReturn(Optional.of(StoreScheduleState.initialize(7)));
        given(temporary.findForUpdate(7, 3L)).willReturn(Optional.of(target));
        lenient().when(temporary.findOverlapping(any(), any(), any())).thenReturn(List.of(target, existing));
        given(idempotency.execute(any(), any())).willAnswer(invocation -> {
            Supplier<com.miriyum.global.idempotency.BusinessResult<?>> work = invocation.getArgument(1);
            var result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(), result.resourceType(),
                    result.resourceId(), mapper.valueToTree(result.data()));
        });

        var result = service.changeEndAt(11, 7, 3L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440003"),
                new TemporaryClosureEndAtRequest(
                        OffsetDateTime.parse("2026-08-03T15:00:00+09:00"), "운영 연장"));

        assertThat(result.data().endAt()).isEqualTo(Instant.parse("2026-08-03T06:00:00Z"));
        StoreClosureAuditEvent event = capturedAuditEvent();
        assertThat(event.getAction()).isEqualTo("END_CHANGED");
        assertTemporaryAudit(event, Instant.parse("2026-08-03T04:00:00Z"),
                Instant.parse("2026-08-03T05:00:00Z"),
                Instant.parse("2026-08-03T06:00:00Z"), TemporaryClosureReason.OTHER);
        assertThat(event.getChangeReason()).isEqualTo("운영 연장");
        assertNotEvaluated(event);
    }

    @Test void cancelsScheduledClosureWithUnknownConflictCountInAudit() {
        TemporaryClosure target = TemporaryClosure.create(7,
                Instant.parse("2026-08-03T04:00:00Z"), Instant.parse("2026-08-03T05:00:00Z"),
                "Asia/Seoul", TemporaryClosureReason.OTHER, null);
        ReflectionTestUtils.setField(target, "id", 3L);
        given(stores.requireSchedulePublicationAuthority(11, 7))
                .willReturn(new StoreScheduleAuthority(7, "Asia/Seoul"));
        given(states.findForUpdateByStoreId(7))
                .willReturn(Optional.of(StoreScheduleState.initialize(7)));
        given(temporary.findForUpdate(7, 3L)).willReturn(Optional.of(target));
        given(idempotency.execute(any(), any())).willAnswer(invocation -> {
            Supplier<com.miriyum.global.idempotency.BusinessResult<?>> work = invocation.getArgument(1);
            var result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(), result.resourceType(),
                    result.resourceId(), mapper.valueToTree(result.data()));
        });

        service.cancel(11, 7, 3L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440004"),
                new TemporaryClosureCancellationRequest("휴점 취소"));

        StoreClosureAuditEvent event = capturedAuditEvent();
        assertThat(event.getAction()).isEqualTo("CANCELLED");
        assertTemporaryAudit(event, Instant.parse("2026-08-03T04:00:00Z"),
                Instant.parse("2026-08-03T05:00:00Z"),
                Instant.parse("2026-08-03T05:00:00Z"), TemporaryClosureReason.OTHER);
        assertThat(event.getChangeReason()).isEqualTo("휴점 취소");
        assertNotEvaluated(event);
    }

    @Test void rejectsClosureStartingInThePast() {
        given(stores.requireSchedulePublicationAuthority(11, 7))
                .willReturn(new StoreScheduleAuthority(7, "Asia/Seoul"));
        given(states.findForUpdateByStoreId(7))
                .willReturn(Optional.of(StoreScheduleState.initialize(7)));
        given(idempotency.execute(any(), any())).willAnswer(invocation -> {
            Supplier<com.miriyum.global.idempotency.BusinessResult<?>> work = invocation.getArgument(1);
            return work.get();
        });

        assertThatThrownBy(() -> service.create(11, 7,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440002"),
                new TemporaryClosureCreateRequest(OffsetDateTime.parse("2026-08-03T11:59:59+09:00"),
                        OffsetDateTime.parse("2026-08-03T13:00:00+09:00"), TemporaryClosureReason.OTHER, null)))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class);
        then(temporary).should(never()).saveAndFlush(any());
    }

    private StoreClosureAuditEvent capturedAuditEvent() {
        ArgumentCaptor<StoreClosureAuditEvent> captor =
                ArgumentCaptor.forClass(StoreClosureAuditEvent.class);
        then(audit).should().save(captor.capture());
        return captor.getValue();
    }

    private void assertNotEvaluated(StoreClosureAuditEvent event) {
        assertThat(event.getPreviousActiveVersion()).isNull();
        assertThat(event.getNewActiveVersion()).isNull();
        assertThat(event.getRequestedAt()).isEqualTo(clock.instant());
        assertThat(event.getOutcome()).isEqualTo(ScheduleAuditOutcome.SUCCEEDED);
        assertThat(event.getConflictCheckStatus()).isEqualTo(ConflictCheckStatus.NOT_EVALUATED);
        assertThat(event.getConflictCount()).isNull();
    }

    private void assertTemporaryAudit(
            StoreClosureAuditEvent event,
            Instant startAt,
            Instant previousEndAt,
            Instant newEndAt,
            TemporaryClosureReason reason
    ) {
        assertThat(event.getClosureStartAt()).isEqualTo(startAt);
        assertThat(event.getPreviousClosureEndAt()).isEqualTo(previousEndAt);
        assertThat(event.getNewClosureEndAt()).isEqualTo(newEndAt);
        assertThat(event.getTemporaryClosureReason()).isEqualTo(reason);
    }
}
