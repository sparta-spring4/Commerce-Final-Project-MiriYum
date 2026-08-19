import { describe, expect, it } from 'vitest'
import {
  WAITING_REGISTRATION_GUIDANCE,
  readPartySize,
  validatePartySize,
  type WaitingRegistrationNoticeCode,
} from './registrationView'

describe('validatePartySize', () => {
  it('한 명 이상이면 통과한다', () => {
    expect(validatePartySize(1)).toBeNull()
    expect(validatePartySize(12)).toBeNull()
  })

  it('0명이면 거절한다', () => {
    expect(validatePartySize(0)).toBe('방문 인원을 한 명 이상 입력해 주세요.')
  })

  it('음수를 거절한다', () => {
    expect(validatePartySize(-1)).toBe('방문 인원을 한 명 이상 입력해 주세요.')
  })

  it('정수가 아니면 거절한다', () => {
    expect(validatePartySize(1.5)).toBe('인원수는 정수로 입력해 주세요.')
    expect(validatePartySize(Number.NaN)).toBe('인원수는 정수로 입력해 주세요.')
  })

  /*
   * 웨이팅 등록 계약의 partySize는 minimum 1만 정하고 상한이 없다.
   * 예약의 인원 상한을 여기로 가져오면 서버가 받는 값을 화면이 대신 거절한다.
   */
  it('계약에 없는 상한을 만들지 않는다', () => {
    expect(validatePartySize(100)).toBeNull()
    expect(validatePartySize(101)).toBeNull()
  })
})

describe('readPartySize', () => {
  it('숫자 문자열을 읽는다', () => {
    expect(readPartySize('4')).toBe(4)
    expect(readPartySize('12')).toBe(12)
  })

  it('빈 입력을 0으로 읽어 검증에 걸리게 한다', () => {
    expect(readPartySize('')).toBe(0)
    expect(validatePartySize(readPartySize(''))).not.toBeNull()
  })

  it('숫자가 아닌 문자를 버린다', () => {
    expect(readPartySize('3명')).toBe(3)
    expect(readPartySize('-2')).toBe(2)
    expect(readPartySize('e')).toBe(0)
  })
})

describe('WAITING_REGISTRATION_GUIDANCE', () => {
  /*
   * 계약이 선언한 판정 결과와 등록 오류를 빠짐없이 안내한다.
   * 목록에서 빠진 사유는 화면에서 조용히 사라지고 일반 오류로 뭉개진다.
   */
  const codes: WaitingRegistrationNoticeCode[] = [
    'PERMISSION_DENIED',
    'POSITION_UNAVAILABLE',
    'OUTSIDE_RADIUS',
    'ACCURACY_INSUFFICIENT',
    'MEASUREMENT_STALE',
    'MANIPULATION_SUSPECTED',
    'LOCATION_PROOF_INVALID',
    'REQUEST_FAILED',
  ]

  it('모든 사유에 제목과 설명이 있다', () => {
    for (const code of codes) {
      const guidance = WAITING_REGISTRATION_GUIDANCE[code]
      expect(guidance.title.length).toBeGreaterThan(0)
      expect(guidance.description.length).toBeGreaterThan(0)
    }
  })

  it('사유마다 제목이 다르다', () => {
    const titles = codes.map((code) => WAITING_REGISTRATION_GUIDANCE[code].title)
    expect(new Set(titles).size).toBe(codes.length)
  })

  /*
   * 위치 판정에서 온 사유는 같은 요청을 다시 보내도 결과가 같다.
   * 증빙 만료도 재측정이 필요하지 재전송으로 풀리지 않는다.
   */
  it('위치 사유는 재측정, 그 밖의 실패만 재전송을 권한다', () => {
    expect(WAITING_REGISTRATION_GUIDANCE.PERMISSION_DENIED.retry).toBe('location')
    expect(WAITING_REGISTRATION_GUIDANCE.POSITION_UNAVAILABLE.retry).toBe('location')
    expect(WAITING_REGISTRATION_GUIDANCE.OUTSIDE_RADIUS.retry).toBe('location')
    expect(WAITING_REGISTRATION_GUIDANCE.ACCURACY_INSUFFICIENT.retry).toBe('location')
    expect(WAITING_REGISTRATION_GUIDANCE.MEASUREMENT_STALE.retry).toBe('location')
    expect(WAITING_REGISTRATION_GUIDANCE.MANIPULATION_SUSPECTED.retry).toBe('location')
    expect(WAITING_REGISTRATION_GUIDANCE.LOCATION_PROOF_INVALID.retry).toBe('location')
    expect(WAITING_REGISTRATION_GUIDANCE.REQUEST_FAILED.retry).toBe('submit')
  })
})
