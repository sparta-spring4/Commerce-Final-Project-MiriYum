package com.miriyum.global.sse;

/** 각 소유 도메인이 MySQL 최신 watermark를 제공하는 공개 SPI다. */
public interface SseHighWatermarkSource {

    boolean supports(SseAudience audience);

    SseSignalState read(SseStreamScope scope);
}
