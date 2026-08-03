package com.miriyum.domain.store.closure.service;

import com.miriyum.domain.store.closure.dto.RegularClosureDraftRequest;
import com.miriyum.domain.store.closure.dto.TemporaryClosureCancellationRequest;
import com.miriyum.domain.store.closure.dto.TemporaryClosureCreateRequest;
import com.miriyum.domain.store.closure.dto.TemporaryClosureEndAtRequest;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationCancellationRequest;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationRequest;
import com.miriyum.global.idempotency.RequestFingerprint;

final class StoreClosureFingerprint {
    private StoreClosureFingerprint() { }

    static String regularDraft(long storeId, RegularClosureDraftRequest request) {
        return RequestFingerprint.of("regular-draft|" + storeId + "|"
                + request.weeklyDays().stream().sorted().toList() + "|"
                + request.dates().stream().sorted().toList());
    }

    static String regularPublication(long storeId, long version, SchedulePublicationRequest request) {
        return RequestFingerprint.of("regular-publication|" + storeId + "|" + version + "|"
                + request.publicationMode() + "|" + request.effectiveAt() + "|" + request.changeReason());
    }

    static String regularCancellation(long storeId, long version, SchedulePublicationCancellationRequest request) {
        return RequestFingerprint.of("regular-cancellation|" + storeId + "|" + version + "|" + request.changeReason());
    }

    static String temporaryCreate(long storeId, TemporaryClosureCreateRequest request) {
        return RequestFingerprint.of("temporary-create|" + storeId + "|" + request.startAt().toInstant()
                + "|" + request.endAt().toInstant() + "|" + request.reason() + "|" + request.publicMessage());
    }

    static String temporaryEnd(long storeId, long closureId, TemporaryClosureEndAtRequest request) {
        return RequestFingerprint.of("temporary-end|" + storeId + "|" + closureId + "|" + request.endAt().toInstant());
    }

    static String temporaryCancel(long storeId, long closureId, TemporaryClosureCancellationRequest request) {
        return RequestFingerprint.of("temporary-cancel|" + storeId + "|" + closureId + "|" + request.changeReason());
    }
}
