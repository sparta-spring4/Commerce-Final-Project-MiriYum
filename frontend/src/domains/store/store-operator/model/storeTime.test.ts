import { describe, expect, it } from 'vitest'
import {
  formatOffset,
  formatStoreDateTime,
  isSupportedTimeZone,
  offsetMinutesAt,
  toStoreInstant,
} from './storeTime'

describe('매장 시간대 변환', () => {
  it('Asia/Seoul은 UTC보다 9시간 앞선다', () => {
    expect(offsetMinutesAt('Asia/Seoul', new Date('2026-08-11T00:00:00Z'))).toBe(
      540,
    )
  })

  it('UTC 시간대는 오프셋이 0이다', () => {
    expect(offsetMinutesAt('UTC', new Date('2026-08-11T00:00:00Z'))).toBe(0)
  })

  it('음수 오프셋도 부호와 자리수를 맞춘다', () => {
    expect(formatOffset(540)).toBe('+09:00')
    expect(formatOffset(0)).toBe('+00:00')
    expect(formatOffset(-330)).toBe('-05:30')
  })

  it('현지 벽시계 시각에 매장 오프셋을 붙인다', () => {
    const instant = toStoreInstant('Asia/Seoul', '2026-09-01T18:30')

    // 계약이 오프셋 포함 RFC 3339를 요구한다. 오프셋을 빼면 서버가 거절한다.
    expect(instant?.iso).toBe('2026-09-01T18:30:00+09:00')
    expect(instant?.epochMs).toBe(Date.parse('2026-09-01T09:30:00Z'))
  })

  it('브라우저 기본 시간대가 아니라 매장 시간대로 해석한다', () => {
    const seoul = toStoreInstant('Asia/Seoul', '2026-09-01T18:30')
    const utc = toStoreInstant('UTC', '2026-09-01T18:30')

    expect(utc?.iso).toBe('2026-09-01T18:30:00+00:00')
    // 같은 벽시계 시각이지만 실제 순간은 9시간 다르다.
    expect((utc?.epochMs ?? 0) - (seoul?.epochMs ?? 0)).toBe(9 * 60 * 60 * 1000)
  })

  it('일광절약시간이 있는 시간대에서도 해당 시점의 오프셋을 쓴다', () => {
    const winter = toStoreInstant('America/New_York', '2026-01-15T12:00')
    const summer = toStoreInstant('America/New_York', '2026-07-15T12:00')

    expect(winter?.iso).toBe('2026-01-15T12:00:00-05:00')
    expect(summer?.iso).toBe('2026-07-15T12:00:00-04:00')
  })

  it('형식이 아니면 null을 돌려준다', () => {
    expect(toStoreInstant('Asia/Seoul', '')).toBeNull()
    expect(toStoreInstant('Asia/Seoul', '2026-09-01')).toBeNull()
  })

  it('표시 시각은 24시간 표기를 쓴다', () => {
    // Node ICU 구성에 따라 ko-KR 오후가 "PM"으로 나오는 환경이 있어 강제한다.
    expect(
      formatStoreDateTime('Asia/Seoul', '2026-09-01T09:30:00Z'),
    ).toBe('2026-09-01 18:30')
  })

  it('해석할 수 없는 시간대 식별자를 걸러낸다', () => {
    expect(isSupportedTimeZone('Asia/Seoul')).toBe(true)
    expect(isSupportedTimeZone('')).toBe(false)
    expect(isSupportedTimeZone('Not/AZone')).toBe(false)
  })
})
