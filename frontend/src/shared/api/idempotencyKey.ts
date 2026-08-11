/**
 * Idempotency-Key는 HTTP 호출이 아니라 **논리적 작업 시도**에 붙는다.
 *
 * 요청 함수 안에서 매번 생성하면 재시도가 새 키를 받아 backend가 중복 실행을 막지 못한다.
 * 반대로 입력이 바뀐 새 제출에 이전 키를 재사용하면 COMMON_007로 거절된다.
 * 따라서 키는 작업 시작 시점에 한 번 만들고 재시도 동안 보존한다.
 */

export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key'

export function createIdempotencyKey(): string {
  return crypto.randomUUID()
}

/**
 * 한 작업 시도의 멱등 키를 붙들고 있는 핸들이다.
 *
 * 같은 입력으로 재시도하는 동안 `current`는 같은 값을 유지한다.
 * 사용자가 입력을 바꿔 새로 제출하면 `renew`로 새 시도를 시작한다.
 */
export interface IdempotencyKeyHandle {
  readonly current: string
  renew(): string
}

export function startIdempotentAttempt(
  initialKey: string = createIdempotencyKey(),
): IdempotencyKeyHandle {
  let key = initialKey
  return {
    get current() {
      return key
    },
    renew() {
      key = createIdempotencyKey()
      return key
    },
  }
}
