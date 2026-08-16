import { describe, expect, test } from 'vitest'
import { ApiContractError, ApiError, NetworkError } from './apiError'
import { CommonErrorCode } from './envelope'
import {
  createIdempotencyKey,
  isOutcomeUnknown,
  startIdempotentAttempt,
} from './idempotencyKey'

function apiError(status: number, code: string): ApiError {
  return new ApiError({ status, code, message: '오류입니다.' })
}

// backend IdempotencyKey가 요구하는 형식이다(#201).
// version은 1~5, variant는 8·9·a·b만 허용하며 어긋나면 COMMON_004로 거절된다.
const BACKEND_UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/

describe('멱등 키', () => {
  test('호출마다 서로 다른 UUID를 만든다', () => {
    expect(createIdempotencyKey()).not.toBe(createIdempotencyKey())
  })

  test('backend가 받아들이는 UUID 형식을 만든다', () => {
    for (let i = 0; i < 50; i += 1) {
      expect(createIdempotencyKey()).toMatch(BACKEND_UUID_PATTERN)
    }
  })

  test('같은 작업 시도를 재시도하는 동안 같은 키를 유지한다', () => {
    const attempt = startIdempotentAttempt()

    const first = attempt.current
    const retry = attempt.current

    expect(retry).toBe(first)
  })

  test('입력이 바뀐 새 시도는 새 키를 쓴다', () => {
    const attempt = startIdempotentAttempt()
    const first = attempt.current

    const renewed = attempt.renew()

    expect(renewed).not.toBe(first)
    expect(attempt.current).toBe(renewed)
  })
})

/**
 * 이 판정이 멱등 키 유지와 사용자 안내를 함께 정한다.
 *
 * 확정 실패를 불명으로 잘못 보면 사용자가 한 번 더 확인할 뿐이지만,
 * 불명을 확정으로 잘못 보면 같은 의도가 두 건의 명령이 된다.
 */
describe('결과 불명 판정', () => {
  test('서버에 닿지 못한 실패는 불명이다', () => {
    expect(isOutcomeUnknown(new NetworkError('연결하지 못했습니다.'))).toBe(true)
  })

  test('2xx를 계약대로 읽지 못한 실패는 불명이다', () => {
    // 명령은 이미 커밋됐고 응답 모양만 어긋났을 수 있다.
    expect(isOutcomeUnknown(new ApiContractError(200, 'malformed'))).toBe(true)
  })

  test('5xx는 처리 도중 끊겼을 수 있으므로 불명이다', () => {
    expect(
      isOutcomeUnknown(apiError(500, CommonErrorCode.INTERNAL_SERVER_ERROR)),
    ).toBe(true)
    expect(
      isOutcomeUnknown(apiError(503, CommonErrorCode.SERVICE_UNAVAILABLE)),
    ).toBe(true)
  })

  test('같은 키가 이미 쓰였다는 응답은 앞선 시도가 살아 있다는 뜻이라 불명이다', () => {
    expect(
      isOutcomeUnknown(apiError(409, CommonErrorCode.IDEMPOTENCY_KEY_REUSED)),
    ).toBe(true)
  })

  test('우리가 분류하지 못한 실패는 불명으로 둔다', () => {
    expect(isOutcomeUnknown(new Error('알 수 없음'))).toBe(true)
  })

  test('서버가 거절을 확정한 4xx 업무 실패는 불명이 아니다', () => {
    expect(
      isOutcomeUnknown(apiError(400, CommonErrorCode.VALIDATION_FAILED)),
    ).toBe(false)
    expect(isOutcomeUnknown(apiError(409, 'RESERVATION_003'))).toBe(false)
    expect(isOutcomeUnknown(apiError(403, 'AUTH_011'))).toBe(false)
    expect(
      isOutcomeUnknown(apiError(429, CommonErrorCode.TOO_MANY_REQUESTS)),
    ).toBe(false)
  })
})
