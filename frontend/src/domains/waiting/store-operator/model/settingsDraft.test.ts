import { describe, expect, test } from 'vitest'
import {
  isDisabling,
  isUnchanged,
  setEnabled,
  setReceptionMode,
  toDraft,
  toUpdateRequest,
  validateDraft,
} from './settingsDraft'
import type { WaitingSetting } from './types'

function setting(overrides: Partial<WaitingSetting> = {}): WaitingSetting {
  return {
    storeId: '7',
    enabled: true,
    receptionMode: 'AUTO',
    advanceOpenMinutes: 30,
    version: 3,
    ...overrides,
  }
}

/**
 * 계약은 `enabled=false`면 `receptionMode`가 반드시 `PAUSED`라고 못박는다.
 * 화면에서 모순된 조합을 만들 수 있으면 서버가 거절하고 운영자는 이유를 모른다.
 */
describe('사용 여부와 접수 모드의 결합', () => {
  test('끄면 접수 모드도 함께 내린다', () => {
    const draft = setEnabled(toDraft(setting({ receptionMode: 'AUTO' })), false)

    expect(draft).toMatchObject({ enabled: false, receptionMode: 'PAUSED' })
  })

  test('꺼진 상태에서 접수 모드를 고르면 사용 여부도 함께 올린다', () => {
    const off = setEnabled(toDraft(setting()), false)

    expect(setReceptionMode(off, 'MANUAL')).toMatchObject({
      enabled: true,
      receptionMode: 'MANUAL',
    })
  })

  test('켠 채로 신규 접수만 막는 조합은 그대로 둔다', () => {
    const draft = setReceptionMode(toDraft(setting()), 'PAUSED')

    expect(draft).toMatchObject({ enabled: true, receptionMode: 'PAUSED' })
  })

  test('모순된 조합은 검증에서 걸린다', () => {
    const broken = { enabled: false, receptionMode: 'AUTO' as const, advanceOpenMinutes: 10 }

    expect(validateDraft(broken).receptionMode).toBeDefined()
  })
})

describe('선오픈 시간 검증', () => {
  test('0분과 180분은 허용한다', () => {
    expect(validateDraft(toDraft(setting({ advanceOpenMinutes: 0 })))).toEqual({})
    expect(validateDraft(toDraft(setting({ advanceOpenMinutes: 180 })))).toEqual({})
  })

  test('범위를 벗어나면 막는다', () => {
    expect(
      validateDraft(toDraft(setting({ advanceOpenMinutes: 181 }))).advanceOpenMinutes,
    ).toBeDefined()
    expect(
      validateDraft(toDraft(setting({ advanceOpenMinutes: -1 }))).advanceOpenMinutes,
    ).toBeDefined()
  })

  test('정수가 아니면 막는다', () => {
    expect(
      validateDraft({ enabled: true, receptionMode: 'AUTO', advanceOpenMinutes: 1.5 })
        .advanceOpenMinutes,
    ).toBeDefined()
  })
})

describe('저장 본문', () => {
  test('조회한 version을 expectedVersion으로 담는다', () => {
    const body = toUpdateRequest(toDraft(setting({ version: 12 })), 12)

    expect(body.expectedVersion).toBe(12)
  })

  test('켜는 요청에는 disableAction을 붙이지 않는다', () => {
    // 계약의 조건부 스키마가 enabled=false인 요청에만 허용한다.
    const body = toUpdateRequest(toDraft(setting()), 3, 'CLOSE_ACTIVE_TEAMS')

    expect(body.disableAction).toBeUndefined()
  })

  test('끄는 요청에는 선택한 처리 방법을 담는다', () => {
    const off = setEnabled(toDraft(setting()), false)

    expect(toUpdateRequest(off, 3, 'KEEP_ACTIVE').disableAction).toBe('KEEP_ACTIVE')
  })
})

describe('변경 판정', () => {
  test('값이 같으면 저장하지 않는다', () => {
    expect(isUnchanged(toDraft(setting()), setting())).toBe(true)
  })

  test('켜져 있던 설정을 끄는 것만 영향 확인 대상이다', () => {
    const off = setEnabled(toDraft(setting({ enabled: true })), false)
    expect(isDisabling(off, setting({ enabled: true }))).toBe(true)

    // 이미 꺼져 있었으면 새로 종결할 팀이 없다.
    expect(isDisabling(off, setting({ enabled: false, receptionMode: 'PAUSED' }))).toBe(
      false,
    )
  })
})
