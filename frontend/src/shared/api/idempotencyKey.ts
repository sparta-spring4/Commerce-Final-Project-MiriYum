import { isApiContractError, isApiError, isNetworkError } from './apiError'
import { CommonErrorCode } from './envelope'

/**
 * Idempotency-Key는 HTTP 호출이 아니라 **논리적 작업 시도**에 붙는다.
 *
 * 요청 함수 안에서 매번 생성하면 재시도가 새 키를 받아 backend가 중복 실행을 막지 못한다.
 * 반대로 입력이 바뀐 새 제출에 이전 키를 재사용하면 COMMON_007로 거절된다.
 * 따라서 키는 작업 시작 시점에 한 번 만들고 재시도 동안 보존한다.
 *
 * 키를 새로 만드는 조건은 **입력이 바뀌었을 때 하나뿐이다**. 실패했다는 사실만으로
 * 새 키를 만들면 안 된다. 응답이 유실된 실패에서는 첫 요청이 서버에 이미 반영됐을
 * 수 있고, 그때 새 키로 다시 보내면 같은 의도가 두 건의 명령이 된다.
 */

export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key'

const SERVER_ERROR_STATUS = 500

/**
 * 서버 반영 여부를 알 수 없는 실패인지 판정한다.
 *
 * 참이면 사용자에게 "다시 시도"를 권하지 않는다. 같은 명령을 한 번 더 보내는 대신
 * 결과를 확인하게 해야 한다. 거짓이면 서버가 명령을 받지 않았다고 확정한 것이므로
 * 같은 키로 그대로 재시도해도 안전하다.
 *
 * 애매하면 불명으로 판정한다. 확정으로 잘못 보면 중복 예약이 생기고, 불명으로
 * 잘못 보면 사용자가 한 번 더 확인할 뿐이다. 손해의 크기가 다르다.
 */
export function isOutcomeUnknown(error: unknown): boolean {
  // 서버에 닿았는지조차 알 수 없다.
  if (isNetworkError(error)) {
    return true
  }
  // 2xx를 받았는데 본문을 계약대로 읽지 못했다. 명령은 이미 반영됐을 수 있다.
  if (isApiContractError(error)) {
    return true
  }
  // 우리가 분류하지 못한 실패다.
  if (!isApiError(error)) {
    return true
  }
  // 서버가 처리 도중 끊겼을 수 있다.
  if (error.status >= SERVER_ERROR_STATUS) {
    return true
  }
  // 같은 키가 이미 쓰였다. 앞선 시도가 살아 있다는 뜻이므로 결과를 확인해야 한다.
  if (error.code === CommonErrorCode.IDEMPOTENCY_KEY_REUSED) {
    return true
  }
  if (error.code === CommonErrorCode.CONCURRENT_MODIFICATION) {
    return true
  }
  return false
}

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
