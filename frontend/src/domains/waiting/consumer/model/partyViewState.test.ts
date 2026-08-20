import { describe, expect, it } from 'vitest'
import {
  WAITING_JOIN_GUIDANCE,
  WAITING_PARTY_GUIDANCE,
  canMutateParty,
  findSelfMembership,
  formatDateTime,
  isInvitationActive,
  isPartyFull,
  memberDisplayName,
  resolveInvitationCode,
  transferCandidates,
  validateInvitationCode,
  type WaitingJoinErrorCode,
  type WaitingPartyErrorCode,
  type WaitingPartyMember,
  type WaitingTeamStatus,
} from './partyViewState'

function member(overrides: Partial<WaitingPartyMember> = {}): WaitingPartyMember {
  return {
    membershipId: 'm-1',
    role: 'MEMBER',
    joinedAt: '2026-08-20T10:00:00+09:00',
    self: false,
    ...overrides,
  }
}

describe('canMutateParty', () => {
  it('WAITING에서만 구성 변경을 허용한다', () => {
    expect(canMutateParty('WAITING')).toBe(true)
  })

  /*
   * 계약은 WAITING 외 모든 상태를 WAITING_015로 거부한다. 호출 이후와 종결을
   * 화면이 따로 열거하면 상태가 늘어날 때 허용하는 쪽으로 새기 쉽다.
   */
  it('호출 이후와 종결 상태를 모두 막는다', () => {
    const blocked: WaitingTeamStatus[] = [
      'CALLED',
      'ARRIVED',
      'CHECKED_IN',
      'CANCELLED',
      'NO_SHOW',
      'CLOSED_BY_STORE',
      'RESERVATION_CONVERTING',
      'RESERVATION_CONVERTED',
    ]
    for (const status of blocked) {
      expect(canMutateParty(status)).toBe(false)
    }
  })
})

describe('isPartyFull', () => {
  /* 상한은 등록한 방문 인원수다. 화면이 별도 상한을 만들지 않는다. */
  it('합류 계정 수가 방문 인원에 도달하면 가득 찬 것으로 본다', () => {
    expect(isPartyFull(2, [member({ membershipId: 'a' })])).toBe(false)
    expect(
      isPartyFull(2, [member({ membershipId: 'a' }), member({ membershipId: 'b' })]),
    ).toBe(true)
  })

  it('방문 인원을 이미 넘긴 상태도 가득 찬 것으로 본다', () => {
    const members = [
      member({ membershipId: 'a' }),
      member({ membershipId: 'b' }),
      member({ membershipId: 'c' }),
    ]
    expect(isPartyFull(2, members)).toBe(true)
  })
})

describe('findSelfMembership', () => {
  it('본인 membership을 찾는다', () => {
    const self = member({ membershipId: 'me', self: true })
    expect(findSelfMembership([member({ membershipId: 'a' }), self])).toBe(self)
  })

  it('본인이 없으면 null을 준다', () => {
    expect(findSelfMembership([member()])).toBeNull()
  })
})

describe('transferCandidates', () => {
  it('본인을 대상 목록에서 뺀다', () => {
    const others = transferCandidates([
      member({ membershipId: 'me', role: 'REPRESENTATIVE', self: true }),
      member({ membershipId: 'a' }),
      member({ membershipId: 'b' }),
    ])
    expect(others.map((candidate) => candidate.membershipId)).toEqual(['a', 'b'])
  })
})

describe('resolveInvitationCode', () => {
  it('발급 완료이고 코드가 있으면 코드를 보여 준다', () => {
    expect(resolveInvitationCode('issued', 'ABCD1234')).toEqual({
      showsCode: true,
      code: 'ABCD1234',
      notice: null,
    })
  })

  /*
   * 멱등 replay는 초대 metadata만 돌려주고 원문 코드를 재생하지 않는다.
   * 상태와 코드가 따로 내려오는 경계에서 어긋난 조합이 실제로 생기므로,
   * 빈 코드를 복사하라고 말하지 않고 계약이 정한 복구 경로를 안내한다.
   */
  it('발급 완료인데 코드가 없으면 replay 안내로 내린다', () => {
    for (const code of [null, undefined, '']) {
      const resolved = resolveInvitationCode('issued', code)
      expect(resolved.showsCode).toBe(false)
      expect(resolved.code).toBeNull()
      expect(resolved.notice).toContain('철회')
    }
  })

  it('replay 상태는 코드 없이 안내만 준다', () => {
    const resolved = resolveInvitationCode('issuedWithoutCode', null)
    expect(resolved.showsCode).toBe(false)
    expect(resolved.notice).toContain('처음 발급할 때만')
  })

  it('미발급·발급 중·만료·철회 상태에는 코드도 안내도 없다', () => {
    for (const view of ['none', 'issuing', 'expired', 'revoked'] as const) {
      expect(resolveInvitationCode(view, 'ABCD1234')).toEqual({
        showsCode: false,
        code: null,
        notice: null,
      })
    }
  })
})

describe('isInvitationActive', () => {
  it('발급된 초대만 살아 있는 것으로 본다', () => {
    expect(isInvitationActive('issued')).toBe(true)
    expect(isInvitationActive('issuedWithoutCode')).toBe(true)
    expect(isInvitationActive('none')).toBe(false)
    expect(isInvitationActive('issuing')).toBe(false)
    expect(isInvitationActive('expired')).toBe(false)
    expect(isInvitationActive('revoked')).toBe(false)
  })
})

describe('formatDateTime', () => {
  it('시각을 사람이 읽는 문자열로 옮긴다', () => {
    const formatted = formatDateTime('2026-08-20T10:15:00+09:00')
    expect(formatted).not.toBeNull()
    expect(formatted).toContain('2026')
  })

  /* 서버 값이 깨져 있으면 Invalid Date를 그대로 보여 주지 않는다. */
  it('읽을 수 없는 값은 null로 준다', () => {
    expect(formatDateTime('nonsense')).toBeNull()
    expect(formatDateTime('')).toBeNull()
  })
})

describe('validateInvitationCode', () => {
  it('값이 있으면 통과한다', () => {
    expect(validateInvitationCode('ABCD1234')).toBeNull()
  })

  it('빈 입력과 공백만 있는 입력을 거절한다', () => {
    expect(validateInvitationCode('')).toBe('초대 코드를 입력해 주세요.')
    expect(validateInvitationCode('   ')).toBe('초대 코드를 입력해 주세요.')
  })

  /*
   * 코드 형식은 계약이 공개하지 않는다. 길이나 문자 집합을 화면이 단정하면
   * 서버가 받는 코드를 화면이 대신 거절한다.
   */
  it('계약에 없는 형식 규칙을 만들지 않는다', () => {
    expect(validateInvitationCode('a')).toBeNull()
    expect(validateInvitationCode('가나다-1234-!@#')).toBeNull()
  })
})

describe('memberDisplayName', () => {
  /* 계약의 WaitingPartyMember에는 이름이 없다. 목록 순서로만 가리킨다. */
  it('목록 순서로 부른다', () => {
    expect(memberDisplayName(member({ role: 'REPRESENTATIVE' }), 0)).toBe('일행 1')
    expect(memberDisplayName(member(), 1)).toBe('일행 2')
  })

  /* 역할은 같은 줄의 뱃지가 말한다. 이름에 겹쳐 넣으면 두 번 읽힌다. */
  it('역할을 이름에 넣지 않는다', () => {
    expect(memberDisplayName(member({ role: 'REPRESENTATIVE' }), 0)).not.toContain(
      '대표자',
    )
  })

  it('본인을 따로 표시한다', () => {
    expect(memberDisplayName(member({ role: 'REPRESENTATIVE', self: true }), 0)).toBe(
      '일행 1 (나)',
    )
    expect(memberDisplayName(member({ self: true }), 2)).toBe('일행 3 (나)')
  })

  it('membershipId를 화면 이름에 넣지 않는다', () => {
    expect(memberDisplayName(member({ membershipId: 'secret-id' }), 0)).not.toContain(
      'secret-id',
    )
  })
})

describe('안내표', () => {
  /*
   * 상태가 어긋난 실패는 같은 요청을 다시 보내도 같은 실패가 돌아온다.
   * 재시도 버튼을 주면 사용자가 눌러 볼 이유 없는 버튼을 반복해서 누른다.
   */
  it('상태 충돌 사유에는 재시도를 권하지 않는다', () => {
    const notRetryable: WaitingPartyErrorCode[] = [
      'VERSION_CONFLICT',
      'MUTATION_NOT_ALLOWED',
      'CAPACITY_EXCEEDED',
      'INVITATION_INVALID',
      'TRANSFER_INVALID',
      'NOT_FOUND',
    ]
    for (const code of notRetryable) {
      expect(WAITING_PARTY_GUIDANCE[code].retryable).toBe(false)
    }
    expect(WAITING_PARTY_GUIDANCE.REQUEST_FAILED.retryable).toBe(true)
  })

  it('합류 실패 사유마다 안내 문구를 갖는다', () => {
    const codes: WaitingJoinErrorCode[] = [
      'INVALID_CODE',
      'EXPIRED_CODE',
      'REVOKED_OR_USED_CODE',
      'PARTY_CAPACITY_EXCEEDED',
      'ACCOUNT_ACTIVE_WAITING_EXISTS',
      'TEAM_STATE_CHANGED',
      'REQUEST_FAILED',
    ]
    for (const code of codes) {
      expect(WAITING_JOIN_GUIDANCE[code].title.length).toBeGreaterThan(0)
      expect(WAITING_JOIN_GUIDANCE[code].description.length).toBeGreaterThan(0)
    }
  })

  it('일시적 실패만 재시도를 권한다', () => {
    expect(WAITING_JOIN_GUIDANCE.REQUEST_FAILED.retryable).toBe(true)
    expect(WAITING_JOIN_GUIDANCE.EXPIRED_CODE.retryable).toBe(false)
    expect(WAITING_JOIN_GUIDANCE.ACCOUNT_ACTIVE_WAITING_EXISTS.retryable).toBe(false)
  })
})
