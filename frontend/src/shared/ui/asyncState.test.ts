import { describe, expect, test } from 'vitest'
import { ApiError, NetworkError } from '../api/apiError'
import { CommonErrorCode } from '../api/envelope'
import { isEmptyResult, toAsyncState } from './asyncState'

function apiError(status: number, code: string): ApiError {
  return new ApiError({ status, code, message: '실패했습니다.' })
}

describe('오류를 화면 상태로 옮긴다', () => {
  test('네트워크 실패는 결과 불명이며 재조회를 안내한다', () => {
    expect(toAsyncState(new NetworkError('연결 실패'))).toEqual({
      state: 'indeterminate',
      action: 'recheck',
    })
  })

  test('401은 로그인 전환을 안내한다', () => {
    expect(toAsyncState(apiError(401, 'AUTH_003'))).toEqual({
      state: 'forbidden',
      action: 'signIn',
    })
  })

  test('403은 로그인 전환을 안내하지 않는다', () => {
    expect(toAsyncState(apiError(403, 'AUTH_011'))).toEqual({
      state: 'forbidden',
      action: 'none',
    })
  })

  test('409 충돌은 재조회로 이어진다', () => {
    expect(
      toAsyncState(apiError(409, CommonErrorCode.IDEMPOTENCY_KEY_REUSED)),
    ).toEqual({ state: 'conflict', action: 'recheck' })
  })

  test('검증 실패는 입력 수정을 안내한다', () => {
    expect(toAsyncState(apiError(400, CommonErrorCode.VALIDATION_FAILED))).toEqual({
      state: 'validationError',
      action: 'fixInput',
    })
  })

  test('서비스 일시 불가는 복구 대기다', () => {
    expect(
      toAsyncState(apiError(503, CommonErrorCode.SERVICE_UNAVAILABLE)),
    ).toEqual({ state: 'awaitingRecovery', action: 'recheck' })
  })

  test('요청 제한은 재시도 가능이다', () => {
    expect(
      toAsyncState(apiError(429, CommonErrorCode.TOO_MANY_REQUESTS)),
    ).toEqual({ state: 'retryable', action: 'retry' })
  })

  test('401과 409는 서로 다른 상태로 구분된다', () => {
    expect(toAsyncState(apiError(401, 'AUTH_003')).state).not.toBe(
      toAsyncState(apiError(409, CommonErrorCode.CONCURRENT_MODIFICATION)).state,
    )
  })
})

describe('빈 결과 판정', () => {
  test('빈 배열은 정상 empty result다', () => {
    expect(isEmptyResult([])).toBe(true)
  })

  test('null도 정상 empty result다', () => {
    expect(isEmptyResult(null)).toBe(true)
  })

  test('값이 있으면 빈 결과가 아니다', () => {
    expect(isEmptyResult([{ code: 'KOREAN' }])).toBe(false)
  })
})
