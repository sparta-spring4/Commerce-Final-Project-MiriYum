import { describe, expect, it } from 'vitest'
import {
  collectErrors,
  normalizePhoneNumber,
  validateAgeConfirmed,
  validateEmail,
  validateNickname,
  validatePassword,
  validatePasswordConfirm,
  validatePhoneNumber,
} from './validation'

describe('validateEmail', () => {
  it('형식에 맞으면 통과한다', () => {
    expect(validateEmail('user@example.com')).toBeNull()
  })

  it('빈 값과 잘못된 형식을 구분해 안내한다', () => {
    expect(validateEmail('')).toBe('이메일을 입력해 주세요.')
    expect(validateEmail('user@')).toBe('이메일 형식으로 입력해 주세요.')
  })
})

describe('validatePassword', () => {
  it('길이와 문자 종류를 모두 만족하면 통과한다', () => {
    expect(validatePassword('Miriyum1!')).toBeNull()
  })

  it('8자 미만은 거절한다', () => {
    expect(validatePassword('Mi1!')).toContain('8~64자')
  })

  it('문자 종류가 3종 미만이면 거절한다', () => {
    expect(validatePassword('onlylowercase')).toContain('3종 이상')
    expect(validatePassword('lowercase123')).toContain('3종 이상')
  })

  it('길이는 code point로 센다', () => {
    // 계약이 code point 기준이라 서로게이트 쌍을 두 글자로 세면 안 된다.
    // 이모지 자체는 계약이 금지하지만 길이 계산 방식은 여기서 고정한다.
    expect([...'𝔘𝔫𝔦𝔠𝔬𝔡𝔢'].length).toBe(7)
  })
})

describe('validatePasswordConfirm', () => {
  it('일치하면 통과한다', () => {
    expect(validatePasswordConfirm('Miriyum1!', 'Miriyum1!')).toBeNull()
  })

  it('다르면 거절한다', () => {
    expect(validatePasswordConfirm('Miriyum1!', 'Miriyum2!')).toBe(
      '비밀번호가 서로 다릅니다.',
    )
  })
})

describe('validateNickname', () => {
  it('2~20자 한글·영문·숫자를 통과시킨다', () => {
    expect(validateNickname('미리냠')).toBeNull()
    expect(validateNickname('user_01')).toBeNull()
  })

  it('길이 범위를 벗어나면 거절한다', () => {
    expect(validateNickname('가')).toContain('2~20자')
    expect(validateNickname('가'.repeat(21))).toContain('2~20자')
  })

  it('허용하지 않는 문자를 거절한다', () => {
    expect(validateNickname('닉네임!')).toContain('사용할 수 있습니다')
  })
})

describe('전화번호', () => {
  it('공백과 하이픈을 제거해 정규화한다', () => {
    expect(normalizePhoneNumber('010-1234-5678')).toBe('01012345678')
    expect(normalizePhoneNumber('010 1234 5678')).toBe('01012345678')
  })

  it('정규화 결과가 계약 형식이면 통과한다', () => {
    expect(validatePhoneNumber('010-1234-5678')).toBeNull()
    expect(validatePhoneNumber('01012345678')).toBeNull()
  })

  it('010으로 시작하지 않거나 자리 수가 다르면 거절한다', () => {
    expect(validatePhoneNumber('02-123-4567')).toContain('010으로 시작')
    expect(validatePhoneNumber('0101234567')).toContain('010으로 시작')
  })
})

describe('validateAgeConfirmed', () => {
  it('확인하지 않으면 거절한다', () => {
    expect(validateAgeConfirmed(false)).toBe('만 14세 이상임을 확인해 주세요.')
    expect(validateAgeConfirmed(true)).toBeNull()
  })
})

describe('collectErrors', () => {
  it('null이 아닌 항목만 남긴다', () => {
    expect(
      collectErrors({ email: null, password: '너무 짧습니다.' }),
    ).toEqual({ password: '너무 짧습니다.' })
  })
})
