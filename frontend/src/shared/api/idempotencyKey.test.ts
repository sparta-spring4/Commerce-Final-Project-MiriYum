import { describe, expect, test } from 'vitest'
import { createIdempotencyKey, startIdempotentAttempt } from './idempotencyKey'

describe('멱등 키', () => {
  test('호출마다 서로 다른 UUID를 만든다', () => {
    expect(createIdempotencyKey()).not.toBe(createIdempotencyKey())
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
