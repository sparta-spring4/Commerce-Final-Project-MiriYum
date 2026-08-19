import { CONSUMER_PATHS } from './routes/paths/consumerPaths'
import { STORE_OPERATOR_PATHS } from './routes/paths/storeOperatorPaths'
import { describe, expect, test } from 'vitest'
import { readReturnTo, withReturnTo } from './returnTo'
const ORIGIN = 'http://localhost:5173'

function read(raw: string): string | null {
  return readReturnTo(`?returnTo=${raw}`, ORIGIN)
}

describe('허용', () => {
  test('원래 경로를 붙였다가 그대로 되읽는다', () => {
    const url = withReturnTo(CONSUMER_PATHS.signIn, '/stores/1?partySize=2')

    expect(readReturnTo(url.slice(url.indexOf('?')), ORIGIN)).toBe(
      '/stores/1?partySize=2',
    )
  })

  test('내부 절대 경로를 허용한다', () => {
    expect(read('%2Fstores%2F1')).toBe('/stores/1')
  })

  test('query를 보존한다', () => {
    expect(read('%2Fstores%3FserviceDate%3D2026-08-07')).toBe(
      '/stores?serviceDate=2026-08-07',
    )
  })

  test('hash를 보존한다', () => {
    expect(read('%2Fstores%2F1%23menus')).toBe('/stores/1#menus')
  })

  test('보존한 값이 없으면 null이다', () => {
    expect(readReturnTo('', ORIGIN)).toBeNull()
  })
})

describe('열린 리다이렉트 거절', () => {
  test('외부 절대 URL을 거절한다', () => {
    expect(read('https%3A%2F%2Fevil.example')).toBeNull()
  })

  test('protocol-relative URL을 거절한다', () => {
    expect(read('%2F%2Fevil.example')).toBeNull()
  })

  // 회귀: 접두사 검사만 하던 구현은 이 값을 통과시켰고
  // 브라우저 URL 파서가 http://evil.example/ 로 정규화했다.
  test('/\\evil.example 우회 입력을 거절한다', () => {
    expect(read('%2F%5Cevil.example')).toBeNull()
  })

  test('역슬래시가 섞인 다른 형태도 거절한다', () => {
    expect(read('%5C%5Cevil.example')).toBeNull()
    expect(read('%2Fpath%5C%5Cevil.example')).toBeNull()
  })

  test('스킴만 다른 외부 이동도 거절한다', () => {
    expect(read('javascript%3Aalert(1)')).toBeNull()
  })

  test('기준 오리진과 포트가 다르면 거절한다', () => {
    expect(readReturnTo('?returnTo=http%3A%2F%2Flocalhost%3A9999%2Fx', ORIGIN)).toBeNull()
  })
})

describe('shell 분리', () => {
  test('두 계정 유형의 로그인 시작점이 다르다', () => {
    expect(CONSUMER_PATHS.signIn).not.toBe(STORE_OPERATOR_PATHS.signIn)
  })
})
