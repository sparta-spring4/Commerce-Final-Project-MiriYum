package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts.Request;
import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts.Snapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession;
import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession.Purpose;
import com.miriyum.domain.reservation.waiting.repository.WaitingLocationProofSessionRepository;
import com.miriyum.domain.store.dto.contract.StoreWaitingLocationProfile;
import com.miriyum.domain.store.service.StoreService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 위치 원문을 호출 스택에서 판정한 뒤 최소 세션만 저장한다. */
@Service
@RequiredArgsConstructor
public class WaitingLocationProofService {

    private final StoreService storeService;
    private final WaitingLocationProofSessionRepository repository;
    private final Clock clock;

    @Transactional
    public Snapshot issue(long accountId, long storeId, Request request) {
        if (accountId <= 0 || storeId <= 0 || request == null) {
            throw new IllegalArgumentException("location proof input must be valid");
        }
        Instant now = clock.instant();
        StoreWaitingLocationProfile store = storeService.getWaitingLocationProfile(storeId);
        WaitingLocationPolicy.Judgment judgment = WaitingLocationPolicy.judge(store, request, now);
        UUID id = UUID.randomUUID();
        WaitingLocationProofSession session = WaitingLocationProofSession.issue(
                id,
                accountId,
                storeId,
                Purpose.WAITING_REGISTRATION,
                judgment.resultCategory(),
                judgment.accuracyCategory(),
                WaitingLocationPolicy.VERSION,
                store.coordinateVersion(),
                now,
                now.plus(WaitingLocationPolicy.PROOF_TTL));
        repository.save(session);
        return new Snapshot(
                session.getId(),
                session.getResultCategory(),
                session.getAccuracyCategory(),
                session.getPolicyVersion(),
                session.getStoreCoordinateVersion(),
                session.getIssuedAt(),
                session.getJudgedAt(),
                session.getExpiresAt());
    }
}
