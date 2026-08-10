package com.miriyum.domain.auth.riskevent;

import com.miriyum.domain.auth.refreshtoken.PendingRefreshTokenRiskEvent;

/** 원문 없는 인증 위험 사건을 멱등하게 영속화하는 포트다. */
public interface AuthRiskEventStore {

    void record(PendingRefreshTokenRiskEvent event);
}
