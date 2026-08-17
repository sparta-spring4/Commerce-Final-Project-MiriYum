import { describe, expect, it } from 'vitest'
import {
  decideAnyCapability,
  decideCapability,
  resolveCapabilities,
  type CapabilityState,
  type PlatformOperatorPermission,
} from './capabilities'

function loaded(
  ...permissions: PlatformOperatorPermission[]
): CapabilityState {
  return { status: 'loaded', permissions: new Set(permissions) }
}

describe('운영자 권한 판정', () => {
  it('권한 계약이 없는 동안에는 모른다고 답한다', () => {
    // 빈 권한 집합을 돌려주면 호출자가 "권한 없음"으로 오해하고 메뉴를 숨긴다.
    // 계약이 생기기 전까지 콘솔이 통째로 빈 화면이 되는 경로다.
    expect(resolveCapabilities()).toEqual({
      status: 'unknown',
      reason: 'contractPending',
    })
  })

  it('모르는 상태에서는 허용도 거부도 하지 않는다', () => {
    const state = resolveCapabilities()

    expect(decideCapability(state, 'AUDIT_READ')).toBe('undetermined')
    expect(decideAnyCapability(state, ['MEMBER_READ_MINIMAL'])).toBe(
      'undetermined',
    )
  })

  it('서버가 준 권한만 허용한다', () => {
    const state = loaded('MEMBER_READ_MINIMAL')

    expect(decideCapability(state, 'MEMBER_READ_MINIMAL')).toBe('allowed')
    expect(decideCapability(state, 'AUDIT_READ')).toBe('denied')
  })

  it('여러 권한 중 하나만 있어도 허용한다', () => {
    // 회원지원 사건은 복구와 이의 심사가 서로 다른 권한이다.
    // 한쪽만 가진 운영자에게도 메뉴는 보여야 한다.
    const state = loaded('ACCOUNT_APPEAL_REVIEW')

    expect(
      decideAnyCapability(state, ['MEMBER_RECOVERY', 'ACCOUNT_APPEAL_REVIEW']),
    ).toBe('allowed')
  })

  it('하나도 없으면 거부한다', () => {
    const state = loaded('AUDIT_READ')

    expect(
      decideAnyCapability(state, ['MEMBER_RECOVERY', 'ACCOUNT_APPEAL_REVIEW']),
    ).toBe('denied')
  })

  /**
   * backend에서 SUPER_ADMIN은 운영자 생성·권한 관리·추가 승인 묶음일 뿐
   * 심사·회원지원·감사 권한을 포함하지 않는다. 프론트가 "슈퍼관리자면 전부"로
   * 취급하면 서버가 거부할 기능을 화면이 그린다.
   */
  it('슈퍼관리자 전용 권한을 가졌다고 다른 업무 권한이 따라오지 않는다', () => {
    const superAdminOnly = loaded(
      'OPERATOR_CREATE',
      'OPERATOR_AUTHORITY_MANAGE',
      'OPERATOR_SUSPEND',
      'ACCOUNT_PERMANENT_SANCTION_APPROVE',
      'PAYMENT_RECOVERY_HIGH_VALUE_APPROVE',
      'BREAK_GLASS_APPROVE',
    )

    expect(decideCapability(superAdminOnly, 'AUDIT_READ')).toBe('denied')
    expect(decideCapability(superAdminOnly, 'MEMBER_READ_MINIMAL')).toBe(
      'denied',
    )
    expect(decideCapability(superAdminOnly, 'ONBOARDING_REVIEW')).toBe('denied')
  })
})
