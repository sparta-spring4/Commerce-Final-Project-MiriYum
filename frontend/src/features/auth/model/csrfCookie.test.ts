import { describe, expect, it } from 'vitest'
import {
  CONSUMER_CSRF_COOKIE,
  STORE_OPERATOR_CSRF_COOKIE,
  readCookie,
} from './csrfCookie'

describe('readCookie', () => {
  it('이름이 정확히 일치하는 값을 읽는다', () => {
    const source = `${CONSUMER_CSRF_COOKIE}=consumer-token`

    expect(readCookie(CONSUMER_CSRF_COOKIE, source)).toBe('consumer-token')
  })

  it('다른 shell의 쿠키를 집어 오지 않는다', () => {
    // 두 이름이 접두사를 공유하지 않더라도, 부분 일치로 찾으면 교차 namespace
    // 값을 읽을 수 있다. 정확 일치만 허용하는지 고정한다.
    const source = `${STORE_OPERATOR_CSRF_COOKIE}=operator-token`

    expect(readCookie(CONSUMER_CSRF_COOKIE, source)).toBeNull()
  })

  it('여러 쿠키 가운데 해당 이름만 고른다', () => {
    const source = `other=1; ${CONSUMER_CSRF_COOKIE}=consumer-token; another=2`

    expect(readCookie(CONSUMER_CSRF_COOKIE, source)).toBe('consumer-token')
  })

  it('이름이 접미사로만 일치하는 쿠키를 고르지 않는다', () => {
    const source = `PREFIX_${CONSUMER_CSRF_COOKIE}=wrong`

    expect(readCookie(CONSUMER_CSRF_COOKIE, source)).toBeNull()
  })

  it('값이 없으면 null이다', () => {
    expect(readCookie(CONSUMER_CSRF_COOKIE, '')).toBeNull()
  })

  it('URL 인코딩된 값을 디코딩한다', () => {
    const source = `${CONSUMER_CSRF_COOKIE}=a%2Bb`

    expect(readCookie(CONSUMER_CSRF_COOKIE, source)).toBe('a+b')
  })
})
