package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.store.dto.storeoperator.ManagedStoreResponse;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.ServiceException;
import java.time.DateTimeException;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Store 공개 Service/DTO만 사용해 Waiting 조회·변경 권한을 판정한다. */
@Component
@RequiredArgsConstructor
public class StoreServiceWaitingAuthorityAdapter implements WaitingStoreAuthorityPort {

    private final StoreService storeService;

    @Override
    public WaitingStoreAuthority requireRead(long operatorAccountId, long storeId) {
        ManagedStoreResponse store = storeService.getManagedStore(operatorAccountId, storeId);
        WaitingStoreAuthority authority = requireApproved(store);
        requireAllowedReadStatus(store.operationStatus());
        return authority;
    }

    @Override
    public WaitingStoreAuthority requireMutation(long operatorAccountId, long storeId) {
        ManagedStoreResponse store = storeService.getManagedStore(operatorAccountId, storeId);
        WaitingStoreAuthority authority = requireApproved(store);
        requireAllowedReadStatus(store.operationStatus());
        if (store.operationStatus() == OperationStatus.CLOSED) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
        return authority;
    }

    private static void requireAllowedReadStatus(OperationStatus status) {
        if (status != OperationStatus.OPEN
                && status != OperationStatus.TEMPORARILY_CLOSED
                && status != OperationStatus.CLOSED) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
    }

    private static WaitingStoreAuthority requireApproved(ManagedStoreResponse store) {
        if (store.verificationStatus() != VerificationStatus.APPROVED) {
            throw new ServiceException(StoreErrorCode.VERIFICATION_STATE_CONFLICT);
        }
        try {
            return new WaitingStoreAuthority(
                    Long.parseLong(store.storeId()),
                    ZoneId.of(store.timeZoneId())
            );
        } catch (NumberFormatException | DateTimeException exception) {
            throw new IllegalStateException("Store public DTO contains invalid authority fields", exception);
        }
    }
}
