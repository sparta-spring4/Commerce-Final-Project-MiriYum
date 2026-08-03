package com.miriyum.domain.store.closure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import com.miriyum.domain.store.closure.dto.TemporaryClosureCreateRequest;
import com.miriyum.domain.store.closure.entity.TemporaryClosure;
import com.miriyum.domain.store.closure.model.TemporaryClosureReason;
import com.miriyum.domain.store.closure.model.TemporaryClosureStatus;
import com.miriyum.domain.store.closure.repository.*;
import com.miriyum.domain.store.core.service.*;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.global.idempotency.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
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
        given(temporary.findOverlapping(any(), any(), any())).willReturn(List.of());
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
        then(audit).should().save(any());
    }
}
