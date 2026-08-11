import { describe, expect, test } from 'vitest'
import { createIdempotencyKey, startIdempotentAttempt } from './idempotencyKey'

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
