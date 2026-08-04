package com.miriyum.domain.store.closure.service;

import com.miriyum.domain.store.closure.dto.*;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationCancellationRequest;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationRequest;
import com.miriyum.domain.store.schedule.service.ScheduleCommandResult;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

@Service
@RequiredArgsConstructor
public class StoreClosureCommandFacade {
    private final StoreClosureService regularService;
    private final TemporaryClosureService temporaryService;

    public ScheduleCommandResult<RegularClosureResponse> createRegularDraft(long actor, long storeId,
            IdempotencyKey key, RegularClosureDraftRequest request) {
        return translate(() -> regularService.createRegularDraft(actor, storeId, key, request));
    }
    public ScheduleCommandResult<RegularClosureResponse> publishRegular(long actor, long storeId, long version,
            IdempotencyKey key, SchedulePublicationRequest request) {
        return translate(() -> regularService.publishRegular(actor, storeId, version, key, request));
    }
    public ScheduleCommandResult<RegularClosureResponse> cancelRegular(long actor, long storeId, long version,
            IdempotencyKey key, SchedulePublicationCancellationRequest request) {
        return translate(() -> regularService.cancelRegularPublication(actor, storeId, version, key, request));
    }
    public ScheduleCommandResult<TemporaryClosureResponse> createTemporary(long actor, long storeId,
            IdempotencyKey key, TemporaryClosureCreateRequest request) {
        return translate(() -> temporaryService.create(actor, storeId, key, request));
    }
    public ScheduleCommandResult<TemporaryClosureResponse> changeTemporaryEnd(long actor, long storeId, long closureId,
            IdempotencyKey key, TemporaryClosureEndAtRequest request) {
        return translate(() -> temporaryService.changeEndAt(actor, storeId, closureId, key, request));
    }
    public ScheduleCommandResult<TemporaryClosureResponse> cancelTemporary(long actor, long storeId, long closureId,
            IdempotencyKey key, TemporaryClosureCancellationRequest request) {
        return translate(() -> temporaryService.cancel(actor, storeId, closureId, key, request));
    }

    private <T> T translate(Supplier<T> work) {
        try { return work.get(); }
        catch (DataIntegrityViolationException | ConcurrencyFailureException | QueryTimeoutException
               | TransactionTimedOutException ex) {
            ServiceException conflict = new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT);
            conflict.initCause(ex);
            throw conflict;
        }
    }
}
