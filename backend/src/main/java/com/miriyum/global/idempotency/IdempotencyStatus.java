package com.miriyum.global.idempotency;

/**
 * 멱등 명령 기록의 처리 상태다.
 *
 * <p>{@code PROCESSING}은 선점 트랜잭션 안에서만 존재하는 일시 상태이며, 성공 시 같은 트랜잭션에서
 * {@code SUCCEEDED}로 확정된다. 실패는 전체 롤백되므로 커밋된 행은 항상 {@code SUCCEEDED}다
 * ({@code FAILED}·TTL·lease 상태는 두지 않는다).</p>
 */
public enum IdempotencyStatus {
    PROCESSING,
    SUCCEEDED
}
