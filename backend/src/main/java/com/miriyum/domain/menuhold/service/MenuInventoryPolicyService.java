package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryPolicyChange;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryPolicyAudit;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryPolicyAuditRepository;
import com.miriyum.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MenuInventoryPolicyService {

    private static final String BUCKET_VERSION_CONSTRAINT =
            "uk_menu_inventory_bucket_key";

    private final MenuInventoryBucketRepository bucketRepository;
    private final MenuInventoryPolicyAuditRepository auditRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuInventoryBucket publishNextPolicy(
            long operatorAccountId,
            String commandType,
            String idempotencyKey,
            long bucketId,
            InventoryPolicyChange change
    ) {
        MenuInventoryBucket selected = bucketRepository.findById(bucketId)
                .orElseThrow(() -> new ServiceException(
                        MenuHoldErrorCode.BUCKET_NOT_FOUND));
        MenuInventoryBucket current = bucketRepository.findCurrentForUpdate(
                        selected.getMenuId(), selected.getServiceDate(),
                        selected.getStartTime(), selected.getEndDate(),
                        selected.getEndTime())
                .orElseThrow(() -> new ServiceException(
                        MenuHoldErrorCode.BUCKET_NOT_FOUND));
        if (!current.getId().equals(bucketId)) {
            throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }

        MenuInventoryBucket next = current.publishNextPolicy(
                change.totalSupply(),
                change.onlineHoldCapacity(),
                change.onsiteCapacity(),
                change.sharedCapacity(),
                change.sharedOnlineAllowed(),
                change.availabilityStatus());
        try {
            MenuInventoryBucket saved = bucketRepository.saveAndFlush(next);
            auditRepository.saveAndFlush(MenuInventoryPolicyAudit.updated(
                    operatorAccountId, commandType, idempotencyKey,
                    current, saved));
            return saved;
        } catch (DataIntegrityViolationException exception) {
            if (containsConstraint(exception, BUCKET_VERSION_CONSTRAINT)) {
                ServiceException conflict = new ServiceException(
                        MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
                conflict.initCause(exception);
                throw conflict;
            }
            throw exception;
        }
    }

    private static boolean containsConstraint(
            Throwable failure,
            String constraint
    ) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains(constraint)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
