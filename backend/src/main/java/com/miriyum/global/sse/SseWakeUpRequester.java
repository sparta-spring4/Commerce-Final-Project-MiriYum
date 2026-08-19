package com.miriyum.global.sse;

import java.util.Collection;

/** 업무 commit 뒤 비내구성 SSE wake-up을 요청하는 경계다. */
public interface SseWakeUpRequester {

    void afterCommit(Collection<SseWakeUpTarget> targets);
}
