import { describe, expect, test } from 'vitest'
import { readReturnTo, withReturnTo } from './returnTo'
import { SIGN_IN_PATH } from './routes'

describe('목적지 보존', () => {
  test('원래 경로를 붙였다가 그대로 되읽는다', () => {
    const url = withReturnTo(SIGN_IN_PATH.consumer, '/stores/1?partySize=2')

    expect(readReturnTo(url.slice(url.indexOf('?')))).toBe('/stores/1?partySize=2')
  })

  test('보존한 값이 없으면 null이다', () => {
    expect(readReturnTo('')).toBeNull()
  })

  test('외부 오리진으로 나가는 값은 받지 않는다', () => {
    expect(readReturnTo('?returnTo=https%3A%2F%2Fevil.example')).toBeNull()
  })

  test('프로토콜 상대 URL도 받지 않는다', () => {
    expect(readReturnTo('?returnTo=%2F%2Fevil.example')).toBeNull()
  })
})

describe('shell 분리', () => {
  test('두 계정 유형의 로그인 시작점이 다르다', () => {
    expect(SIGN_IN_PATH.consumer).not.toBe(SIGN_IN_PATH.storeOperator)
  })
})
