import { describe, expect, it } from 'vitest'
import { readCookie } from './readCookie'

describe('readCookie', () => {
  it('이름이 정확히 일치하는 값을 읽는다', () => {
    expect(readCookie('TARGET_TOKEN', 'TARGET_TOKEN=token')).toBe('token')
  })

  it('다른 namespace의 쿠키를 집어 오지 않는다', () => {
    expect(readCookie('TARGET_TOKEN', 'OTHER_TOKEN=other')).toBeNull()
  })

  it('여러 쿠키 가운데 해당 이름만 고른다', () => {
    expect(readCookie('TARGET_TOKEN', 'other=1; TARGET_TOKEN=token; another=2')).toBe('token')
  })

  it('이름이 접미사로만 일치하는 쿠키를 고르지 않는다', () => {
    expect(readCookie('TARGET_TOKEN', 'PREFIX_TARGET_TOKEN=wrong')).toBeNull()
  })

  it('값이 없으면 null이다', () => {
    expect(readCookie('TARGET_TOKEN', '')).toBeNull()
  })

  it('URL 인코딩된 값을 디코딩한다', () => {
    expect(readCookie('TARGET_TOKEN', 'TARGET_TOKEN=a%2Bb')).toBe('a+b')
  })
})
