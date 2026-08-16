import { useState } from 'react'
import { createIdempotencyKey, isOutcomeUnknown } from './idempotencyKey'

/**
 * 요청 지문에 묶인 멱등 키 한 개를 관리한다.
 *
 * 계약의 `request_fingerprint`는 method·route·path parameter·정규화한 query와
 * 승인 body field를 함께 묶는다. 같은 키를 다른 지문으로 보내면 서버가
 * `COMMON_007`로 거절하므로, 키는 지문과 함께 움직여야 한다.
 *
 * - 지문이 그대로면 같은 키로 재시도해 결과를 하나로 수렴시킨다.
 * - 지문이 바뀌면 새 명령이므로 새 키를 만든다.
 * - 단, 결과 불명 뒤에는 지문을 바꾼 재전송을 막는다. 앞선 명령이 이미
 *   반영됐을 수 있는데 새 키로 다른 지문을 보내면 두 건이 되기 때문이다.
 *   이때는 사용자를 최신 상태 확인으로 보낸다.
 */
export interface IdempotentAttempt {
  /**
   * 마지막 전송의 서버 반영 여부를 모르는 상태.
   *
   * 화면은 이 값으로 "같은 키 재전송(결과 확인)"을 안내하고, 지문이 바뀌지
   * 않도록 입력을 잠글 수 있다.
   */
  readonly outcomeUnknown: boolean
  /** 결과 불명 뒤 지문이 바뀌어 더 보낼 수 없는 상태. */
  readonly blocked: boolean
  /**
   * 이번 전송에 쓸 키를 확정한다.
   * `blocked`면 null을 돌려주므로 호출자는 요청을 보내지 않는다.
   */
  begin: () => string | null
  /** 응답을 받은 뒤 결과 불명 여부를 기록한다. 성공이면 error에 null을 넘긴다. */
  settle: (error: unknown) => void
}

export function useIdempotentAttempt(fingerprint: string): IdempotentAttempt {
  const [attempt, setAttempt] = useState(() => ({
    key: createIdempotencyKey(),
    fingerprint,
    outcomeUnknown: false,
  }))

  const fingerprintChanged = attempt.fingerprint !== fingerprint
  const blocked = attempt.outcomeUnknown && fingerprintChanged

  return {
    outcomeUnknown: attempt.outcomeUnknown,
    blocked,
    begin() {
      if (blocked) {
        return null
      }
      if (!fingerprintChanged) {
        return attempt.key
      }
      const key = createIdempotencyKey()
      setAttempt({ key, fingerprint, outcomeUnknown: false })
      return key
    },
    settle(error: unknown) {
      const outcomeUnknown = error !== null && isOutcomeUnknown(error)
      setAttempt((current) => ({ ...current, outcomeUnknown }))
    },
  }
}
