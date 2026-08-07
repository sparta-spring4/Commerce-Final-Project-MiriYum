import { describe, expect, test } from 'vitest'
import { isApiErrorBody, isValidationErrorDetail } from './envelope'

/**
 * 런타임 가드 테스트.
 *
 * 서버에서 온 값은 unknown이라 생성 타입이 보호하지 못한다. 여기서는 실제 값으로
 * 판정 동작을 확인한다. 컴파일 시점 계약 일치는 client.types.test.ts가 담당한다.
 */

describe('isValidationErrorDetail', () => {
  test('field와 reason이 문자열이면 통과한다', () => {
    expect(
      isValidationErrorDetail({ field: 'nickname', reason: '길이를 확인해 주세요.' }),
    ).toBe(true)
  })

  test('요청 전체 오류를 뜻하는 $ 경로도 통과한다', () => {
    expect(isValidationErrorDetail({ field: '$', reason: '본문이 필요합니다.' })).toBe(
      true,
    )
  })

  test('reason 대신 message를 쓰면 거절한다', () => {
    expect(isValidationErrorDetail({ field: 'nickname', message: '길이 오류' })).toBe(
      false,
    )
  })

  test('reason이 없으면 거절한다', () => {
    expect(isValidationErrorDetail({ field: 'nickname' })).toBe(false)
  })

  test('field가 없으면 거절한다', () => {
    expect(isValidationErrorDetail({ reason: '길이 오류' })).toBe(false)
  })

  test('field나 reason이 문자열이 아니면 거절한다', () => {
    expect(isValidationErrorDetail({ field: 1, reason: '길이 오류' })).toBe(false)
    expect(isValidationErrorDetail({ field: 'nickname', reason: 42 })).toBe(false)
    expect(isValidationErrorDetail({ field: 'nickname', reason: null })).toBe(false)
  })

  test('객체가 아니면 거절한다', () => {
    expect(isValidationErrorDetail(null)).toBe(false)
    expect(isValidationErrorDetail('nickname')).toBe(false)
    expect(isValidationErrorDetail(['nickname'])).toBe(false)
    expect(isValidationErrorDetail(undefined)).toBe(false)
  })
})

describe('isApiErrorBody — code와 message', () => {
  test('code와 message가 문자열이면 통과한다', () => {
    expect(isApiErrorBody({ code: 'COMMON_001', message: '입력값이 올바르지 않습니다.' })).toBe(
      true,
    )
  })

  test('code나 message가 없으면 거절한다', () => {
    expect(isApiErrorBody({ message: '오류' })).toBe(false)
    expect(isApiErrorBody({ code: 'COMMON_001' })).toBe(false)
  })

  test('객체가 아니면 거절한다', () => {
    expect(isApiErrorBody(null)).toBe(false)
    expect(isApiErrorBody('COMMON_001')).toBe(false)
    expect(isApiErrorBody([{ code: 'COMMON_001', message: '오류' }])).toBe(false)
  })
})

describe('isApiErrorBody — details', () => {
  const base = { code: 'COMMON_001', message: '입력값이 올바르지 않습니다.' }

  test('details가 없는 일반 오류 본문을 허용한다', () => {
    expect(isApiErrorBody(base)).toBe(true)
  })

  test('details가 undefined면 허용한다', () => {
    expect(isApiErrorBody({ ...base, details: undefined })).toBe(true)
  })

  test('field와 reason을 가진 details를 허용한다', () => {
    expect(
      isApiErrorBody({
        ...base,
        details: [
          { field: 'nickname', reason: '길이를 확인해 주세요.' },
          { field: 'menuSelections[0].quantity', reason: '1 이상이어야 합니다.' },
        ],
      }),
    ).toBe(true)
  })

  test('field와 message를 가진 details를 거절한다', () => {
    expect(
      isApiErrorBody({ ...base, details: [{ field: 'nickname', message: '길이 오류' }] }),
    ).toBe(false)
  })

  test('reason이 누락된 항목이 있으면 거절한다', () => {
    expect(isApiErrorBody({ ...base, details: [{ field: 'nickname' }] })).toBe(false)
  })

  test('항목 하나만 어긋나도 전체를 거절한다', () => {
    expect(
      isApiErrorBody({
        ...base,
        details: [
          { field: 'nickname', reason: '길이를 확인해 주세요.' },
          { field: 'price', message: '숫자여야 합니다.' },
        ],
      }),
    ).toBe(false)
  })

  // 계약이 minItems: 1이라 빈 배열은 details를 보내지 않은 것과 다르다.
  test('빈 details 배열을 거절한다', () => {
    expect(isApiErrorBody({ ...base, details: [] })).toBe(false)
  })

  test('details가 배열이 아니면 거절한다', () => {
    expect(
      isApiErrorBody({ ...base, details: { field: 'nickname', reason: '오류' } }),
    ).toBe(false)
    expect(isApiErrorBody({ ...base, details: 'nickname' })).toBe(false)
    expect(isApiErrorBody({ ...base, details: null })).toBe(false)
  })
})
