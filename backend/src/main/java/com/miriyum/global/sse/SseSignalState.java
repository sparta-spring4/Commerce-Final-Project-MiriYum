package com.miriyum.global.sse;

import java.util.Set;

/** MySQL에서 읽은 현재 watermark와 이 연결을 깨울 routing 대상이다. */
public record SseSignalState(long watermark, Set<SseWakeUpTarget> wakeUpTargets) {

    public SseSignalState {
        if (watermark < 0 || wakeUpTargets == null || wakeUpTargets.isEmpty()) {
            throw new IllegalArgumentException("SSE signal state is invalid");
        }
        wakeUpTargets = Set.copyOf(wakeUpTargets);
    }
}
