package com.miriyum.domain.notification.service;

/** Waiting 상태 사건이 미발송 입장 임박 작업을 최신 상태로 깨우는 공개 경계다. */
public interface WaitingNotificationReevaluationService {

    void reevaluate(long waitingTeamId, long waitingStatusEventId, long eventSequence);
}
