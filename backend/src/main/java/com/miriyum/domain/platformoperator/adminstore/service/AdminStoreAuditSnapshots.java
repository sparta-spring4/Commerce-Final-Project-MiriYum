package com.miriyum.domain.platformoperator.adminstore.service;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.CaseData;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.CaseDetail;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.ImpactPreviewData;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.SanctionData;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementResult;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.StoreSnapshot;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produces Hibernate JSON-safe audit values without retaining domain response records. */
final class AdminStoreAuditSnapshots {
    private AdminStoreAuditSnapshots() {
    }

    static Map<String, Object> caseData(CaseData value) {
        Map<String, Object> result = baseCase(value.caseId(), value.storeId(), value.violationType(),
                value.evidenceReferences(), value.policyVersion(), value.status(), value.caseVersion(),
                value.createdBy(), value.assignedOperatorId(), value.createdAt());
        return Map.copyOf(result);
    }

    static Map<String, Object> caseDetail(CaseDetail value) {
        Map<String, Object> result = baseCase(value.caseId(), value.storeId(), value.violationType(),
                value.evidenceReferences(), value.policyVersion(), value.status(), value.caseVersion(),
                value.createdBy(), value.assignedOperatorId(), value.createdAt());
        result.put("sanctions", value.sanctions().stream().map(AdminStoreAuditSnapshots::sanction).toList());
        return Map.copyOf(result);
    }

    static Map<String, Object> sanction(SanctionData value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sanctionId", value.sanctionId());
        result.put("caseId", value.caseId());
        result.put("storeId", value.storeId());
        result.put("type", value.type().name());
        result.put("status", value.status().name());
        result.put("sanctionVersion", value.sanctionVersion());
        result.put("storeEnforcementVersion", value.storeEnforcementVersion());
        result.put("restrictedFeatures", enumNames(value.restrictedFeatures()));
        putTemporal(result, "startsAt", value.startsAt());
        putTemporal(result, "endsAt", value.endsAt());
        putTemporal(result, "createdAt", value.createdAt());
        return Map.copyOf(result);
    }

    static Map<String, Object> store(EnforcementResult value) {
        Map<String, Object> result = storeState(value.storeId(), value.operationStatus(), value.reservationEnabled(),
                value.menuHoldEnabled(), value.pickupEnabled(), value.enforcementVersion());
        result.put("waitingAllowed", value.waitingAllowed());
        result.put("storeManagementAllowed", value.storeManagementAllowed());
        result.put("restrictedFeatures", enumNames(value.restrictedFeatures()));
        return Map.copyOf(result);
    }

    static Map<String, Object> store(StoreSnapshot value) {
        Map<String, Object> result = storeState(value.storeId(), value.operationStatus(), value.reservationEnabled(),
                value.menuHoldEnabled(), value.pickupEnabled(), value.enforcementVersion());
        result.put("name", value.name());
        result.put("storeOperatorAccountId", value.storeOperatorAccountId());
        putTemporal(result, "createdAt", value.createdAt());
        return Map.copyOf(result);
    }

    static Map<String, Object> preview(ImpactPreviewData value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previewId", value.previewId());
        result.put("caseId", value.caseId());
        result.put("storeId", value.storeId());
        result.put("caseVersion", value.caseVersion());
        result.put("storeEnforcementVersion", value.storeEnforcementVersion());
        result.put("confirmedReservationCount", value.confirmedReservationCount());
        result.put("activeWaitingTeamCount", value.activeWaitingTeamCount());
        result.put("confirmedPickupCount", value.confirmedPickupCount());
        result.put("unsettledPaymentCount", value.unsettledPaymentCount());
        result.put("digest", value.digest());
        putTemporal(result, "expiresAt", value.expiresAt());
        return Map.copyOf(result);
    }

    private static Map<String, Object> baseCase(String caseId, long storeId, String violationType,
            Collection<String> evidenceReferences, String policyVersion, Enum<?> status, long caseVersion,
            long createdBy, Long assignedOperatorId, TemporalAccessor createdAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("storeId", storeId);
        result.put("violationType", violationType);
        result.put("evidenceReferences", evidenceReferences.stream().sorted().toList());
        result.put("policyVersion", policyVersion);
        result.put("status", status.name());
        result.put("caseVersion", caseVersion);
        result.put("createdBy", createdBy);
        if (assignedOperatorId != null) result.put("assignedOperatorId", assignedOperatorId);
        putTemporal(result, "createdAt", createdAt);
        return result;
    }

    private static Map<String, Object> storeState(long storeId, Enum<?> operationStatus,
            boolean reservationEnabled, boolean menuHoldEnabled, boolean pickupEnabled, long enforcementVersion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storeId", storeId);
        result.put("operationStatus", operationStatus.name());
        result.put("reservationEnabled", reservationEnabled);
        result.put("menuHoldEnabled", menuHoldEnabled);
        result.put("pickupEnabled", pickupEnabled);
        result.put("enforcementVersion", enforcementVersion);
        return result;
    }

    private static List<String> enumNames(Collection<? extends Enum<?>> values) {
        List<String> names = new ArrayList<>(values.size());
        values.forEach(value -> names.add(value.name()));
        return names.stream().sorted().toList();
    }

    private static void putTemporal(Map<String, Object> result, String key, TemporalAccessor value) {
        if (value != null) result.put(key, value.toString());
    }
}
