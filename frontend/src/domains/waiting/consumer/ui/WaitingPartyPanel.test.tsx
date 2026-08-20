import { fireEvent, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../../test/renderWithProviders'
import type {
  WaitingCopyResult,
  WaitingInvitationView,
  WaitingPartyErrorView,
  WaitingPartyMember,
  WaitingPartyPendingAction,
  WaitingPartyRole,
  WaitingTeamStatus,
  WaitingTransferOffer,
} from '../model/partyViewState'
import { WaitingPartyPanel } from './WaitingPartyPanel'

const REPRESENTATIVE: WaitingPartyMember = {
  membershipId: 'ms-rep',
  role: 'REPRESENTATIVE',
  joinedAt: '2026-08-20T10:00:00+09:00',
  self: false,
}

const MEMBER: WaitingPartyMember = {
  membershipId: 'ms-member',
  role: 'MEMBER',
  joinedAt: '2026-08-20T10:05:00+09:00',
  self: false,
}

/** 대표자로 로그인한 상태의 기본 목록. */
const AS_REPRESENTATIVE: WaitingPartyMember[] = [
  { ...REPRESENTATIVE, self: true },
  MEMBER,
]

/** 구성원으로 로그인한 상태의 기본 목록. */
const AS_MEMBER: WaitingPartyMember[] = [
  REPRESENTATIVE,
  { ...MEMBER, self: true },
]

interface Overrides {
  teamStatus?: WaitingTeamStatus
  teamVersion?: number
  partySize?: number
  memberships?: WaitingPartyMember[]
  currentUserRole?: WaitingPartyRole
  invitation?: WaitingInvitationView
  invitationCode?: string | null
  invitationExpiresAt?: string | null
  copyResult?: WaitingCopyResult
  shareSupported?: boolean
  transferOffer?: WaitingTransferOffer | null
  pendingAction?: WaitingPartyPendingAction | null
  errorView?: WaitingPartyErrorView | null
}

/**
 * 패널은 상태를 스스로 만들지 않는다. 각 테스트가 표현할 상태를 그대로 넣고
 * 콜백 호출만 관찰한다. API 호출·멱등 키·재조회는 이 패널 밖에 있다.
 */
function renderPanel(overrides: Overrides = {}) {
  const handlers = {
    onIssueInvitation: vi.fn(),
    onCopyInvitation: vi.fn(),
    onShareInvitation: vi.fn(),
    onRevokeInvitation: vi.fn(),
    onDepart: vi.fn(),
    onRemoveMember: vi.fn(),
    onProposeTransfer: vi.fn(),
    onAcceptTransfer: vi.fn(),
    onRejectTransfer: vi.fn(),
    onRevokeTransfer: vi.fn(),
    onRetry: vi.fn(),
  }

  function panel(current: Overrides) {
    const role = current.currentUserRole ?? 'REPRESENTATIVE'
    const memberships =
      current.memberships ??
      (role === 'REPRESENTATIVE' ? AS_REPRESENTATIVE : AS_MEMBER)

    return (
      <WaitingPartyPanel
        teamStatus={current.teamStatus ?? 'WAITING'}
        teamVersion={current.teamVersion ?? 3}
        partySize={current.partySize ?? 4}
        memberships={memberships}
        currentUserRole={role}
        invitation={current.invitation ?? 'none'}
        invitationCode={current.invitationCode ?? null}
        invitationExpiresAt={current.invitationExpiresAt ?? null}
        copyResult={current.copyResult ?? 'idle'}
        shareSupported={current.shareSupported ?? false}
        transferOffer={current.transferOffer ?? null}
        pendingAction={current.pendingAction ?? null}
        errorView={current.errorView ?? null}
        {...handlers}
      />
    )
  }

  const view = renderWithProviders(panel(overrides))

  /*
   * 서버 응답으로 상태가 바뀌는 흐름을 흉내내려면 같은 트리에 새 props를
   * 흘려 넣어야 한다. 다시 render하면 컴포넌트 내부 상태(열린 다이얼로그,
   * 고른 대상)가 초기화되어 그 흐름을 검증할 수 없다.
   */
  function update(next: Overrides) {
    view.rerender(panel({ ...overrides, ...next }))
  }

  return { ...view, ...handlers, update }
}

describe('일행 패널 - 인원과 역할 표시', () => {
  it('방문 인원과 합류한 계정 수를 구분해 보여 준다', () => {
    renderPanel({ partySize: 4, memberships: AS_REPRESENTATIVE })

    expect(screen.getByText('방문 인원')).toBeInTheDocument()
    expect(screen.getByText('4명')).toBeInTheDocument()
    expect(screen.getByText('합류한 계정')).toBeInTheDocument()
    expect(screen.getByText('2개')).toBeInTheDocument()
  })

  it('대표자와 구성원의 역할을 목록에 표시한다', () => {
    renderPanel({ currentUserRole: 'MEMBER' })

    const rows = screen.getAllByRole('listitem')
    expect(within(rows[0]).getByText('대표자')).toBeInTheDocument()
    expect(within(rows[1]).getByText('구성원')).toBeInTheDocument()
  })

  it('현재 로그인 사용자의 membership을 표시한다', () => {
    renderPanel({ currentUserRole: 'MEMBER' })

    const rows = screen.getAllByRole('listitem')
    expect(within(rows[1]).getByText('나')).toBeInTheDocument()
    expect(within(rows[1]).getByText('일행 2 (나)')).toBeInTheDocument()
    expect(screen.getByText('내 역할: 구성원')).toBeInTheDocument()
  })

  /* 계약에 이름 필드가 없다. 화면이 membershipId를 대신 노출하지 않는다. */
  it('membershipId를 화면에 노출하지 않는다', () => {
    renderPanel()

    expect(screen.queryByText(/ms-member/)).not.toBeInTheDocument()
    expect(screen.queryByText(/ms-rep/)).not.toBeInTheDocument()
  })
})

describe('일행 패널 - 역할별 action 노출', () => {
  it('대표자에게 초대와 구성원 관리 action을 준다', () => {
    renderPanel({ currentUserRole: 'REPRESENTATIVE' })

    expect(screen.getByRole('button', { name: '일행 초대' })).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '일행 2 내보내기' }),
    ).toBeInTheDocument()
  })

  /*
   * 팀에는 대표자의 활성 membership이 항상 있어야 한다. 대표자가 빠지려면
   * 이탈이 아니라 대표자 이전이 먼저다.
   */
  it('대표자에게 본인 이탈 버튼을 주지 않는다', () => {
    renderPanel({ currentUserRole: 'REPRESENTATIVE' })

    expect(
      screen.queryByRole('button', { name: '웨이팅 일행에서 나가기' }),
    ).not.toBeInTheDocument()
  })

  it('구성원에게 본인 이탈 action을 준다', () => {
    renderPanel({ currentUserRole: 'MEMBER' })

    expect(
      screen.getByRole('button', { name: '웨이팅 일행에서 나가기' }),
    ).toBeInTheDocument()
  })

  it('구성원은 초대를 발급할 수 없다', () => {
    renderPanel({ currentUserRole: 'MEMBER' })

    expect(screen.queryByRole('button', { name: '일행 초대' })).not.toBeInTheDocument()
    expect(screen.queryByText('일행 초대')).not.toBeInTheDocument()
  })

  it('구성원은 다른 구성원을 제거할 수 없다', () => {
    const memberships: WaitingPartyMember[] = [
      REPRESENTATIVE,
      { ...MEMBER, self: true },
      { ...MEMBER, membershipId: 'ms-other' },
    ]
    renderPanel({ currentUserRole: 'MEMBER', memberships })

    expect(screen.queryByRole('button', { name: /내보내기/ })).not.toBeInTheDocument()
  })

  it('구성원에게 대표자 이전 제안 폼을 주지 않는다', () => {
    renderPanel({ currentUserRole: 'MEMBER' })

    expect(screen.queryByLabelText('대표자를 넘길 구성원')).not.toBeInTheDocument()
  })
})

describe('일행 패널 - 초대 코드 상태', () => {
  it('미발급 상태에서는 코드 없이 발급 버튼만 둔다', () => {
    renderPanel({ invitation: 'none' })

    expect(screen.getByText('아직 발급한 초대가 없습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '일행 초대' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '코드 복사' })).not.toBeInTheDocument()
  })

  it('발급 중에는 버튼을 진행 중으로 두고 다시 누를 수 없게 한다', () => {
    const { onIssueInvitation } = renderPanel({
      invitation: 'issuing',
      pendingAction: { kind: 'issueInvitation' },
    })

    const button = screen.getByRole('button', { name: '일행 초대' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')

    fireEvent.click(button)
    expect(onIssueInvitation).not.toHaveBeenCalled()
  })

  it('발급 완료면 코드와 만료 시각, 복사 action을 보여 준다', () => {
    renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
      invitationExpiresAt: '2026-08-20T10:15:00+09:00',
    })

    expect(screen.getByText('JOIN-4821')).toBeInTheDocument()
    expect(screen.getByText(/까지 사용할 수 있습니다\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '코드 복사' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '초대 철회' })).toBeInTheDocument()
  })

  it('복사를 누르면 코드를 그대로 콜백에 넘긴다', () => {
    const { onCopyInvitation } = renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
    })

    fireEvent.click(screen.getByRole('button', { name: '코드 복사' }))
    expect(onCopyInvitation).toHaveBeenCalledWith('JOIN-4821')
  })

  it('복사 성공과 실패를 aria-live 영역으로 알린다', () => {
    const { unmount } = renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
      copyResult: 'copied',
    })

    const live = screen.getByRole('status')
    expect(live).toHaveAttribute('aria-live', 'polite')
    expect(live).toHaveTextContent('초대 코드를 복사했습니다.')
    unmount()

    renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
      copyResult: 'failed',
    })
    expect(screen.getByRole('status')).toHaveTextContent(
      '복사하지 못했습니다. 코드를 직접 선택해 복사해 주세요.',
    )
  })

  it('공유를 지원하면 공유 action을 주고, 미지원이면 복사 안내로 대신한다', () => {
    const { unmount, onShareInvitation } = renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
      shareSupported: true,
    })

    fireEvent.click(screen.getByRole('button', { name: '공유하기' }))
    expect(onShareInvitation).toHaveBeenCalledWith('JOIN-4821')
    unmount()

    renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
      shareSupported: false,
    })
    expect(screen.queryByRole('button', { name: '공유하기' })).not.toBeInTheDocument()
    expect(
      screen.getByText('이 브라우저는 공유를 지원하지 않습니다. 코드를 복사해 전달해 주세요.'),
    ).toBeInTheDocument()
  })

  /*
   * 멱등 replay는 원문 코드를 재생하지 않는다. 코드 자리를 비워 둔 채 복사하라고
   * 하지 않고, 계약이 정한 복구 경로(철회 후 재발급)를 안내한다.
   */
  it('replay로 원문 코드가 없으면 복사 대신 재발급을 안내한다', () => {
    renderPanel({ invitation: 'issuedWithoutCode', invitationCode: null })

    expect(screen.getByText('초대 코드를 다시 볼 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByText(/철회하고 새로 발급/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '코드 복사' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '초대 철회' })).toBeInTheDocument()
  })

  it('발급 완료로 내려왔지만 코드가 비어 있어도 코드를 지어내지 않는다', () => {
    renderPanel({ invitation: 'issued', invitationCode: '' })

    expect(screen.getByText('초대 코드를 다시 볼 수 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '코드 복사' })).not.toBeInTheDocument()
  })

  it('만료된 초대는 만료를 알리고 새 발급을 권한다', () => {
    renderPanel({ invitation: 'expired' })

    expect(screen.getByText('초대가 만료되었습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '일행 초대' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '초대 철회' })).not.toBeInTheDocument()
  })

  it('철회한 초대는 철회 결과를 남긴다', () => {
    renderPanel({ invitation: 'revoked' })

    expect(screen.getByText('초대를 철회했습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '코드 복사' })).not.toBeInTheDocument()
  })

  it('자리가 다 차면 초대를 발급하지 못하게 막고 이유를 알린다', () => {
    renderPanel({ partySize: 2, memberships: AS_REPRESENTATIVE })

    expect(screen.getByText('일행 자리가 모두 찼습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '일행 초대' })).toBeDisabled()
  })

  /*
   * 발급 자체는 서버가 받지만 그 코드로는 아무도 합류할 수 없다. 자리가 없으면
   * 살아 있는 초대가 있어도 새로 발급하지 않고, 철회만 남긴다.
   */
  it('자리가 다 차면 살아 있는 초대가 있어도 재발급을 막는다', () => {
    renderPanel({
      partySize: 2,
      memberships: AS_REPRESENTATIVE,
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
    })

    expect(screen.getByRole('button', { name: '새 초대 코드 발급' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '초대 철회' })).toBeEnabled()
  })
})

describe('일행 패널 - 초대 철회 확인', () => {
  it('철회는 확인 다이얼로그를 거친다', () => {
    const { onRevokeInvitation } = renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
    })

    fireEvent.click(screen.getByRole('button', { name: '초대 철회' }))

    const dialog = screen.getByRole('dialog', { name: '초대를 철회할까요?' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(onRevokeInvitation).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: '초대 철회' }))
    expect(onRevokeInvitation).toHaveBeenCalledTimes(1)
  })

  it('확인 다이얼로그를 취소하면 아무 일도 하지 않는다', () => {
    const { onRevokeInvitation } = renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
    })

    fireEvent.click(screen.getByRole('button', { name: '초대 철회' }))
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: '취소' }),
    )

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(onRevokeInvitation).not.toHaveBeenCalled()
  })

  /*
   * 철회가 끝난 뒤 확인 표시가 남아 있으면, 대표자가 새 초대를 발급하는 순간
   * 아무도 누르지 않은 확인 창이 저절로 열린다.
   */
  it('철회한 뒤 새 초대를 발급해도 확인 창이 저절로 열리지 않는다', () => {
    const { update } = renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
    })

    fireEvent.click(screen.getByRole('button', { name: '초대 철회' }))
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: '초대 철회' }),
    )

    update({ invitation: 'revoked', invitationCode: null })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    update({ invitation: 'issued', invitationCode: 'JOIN-9999' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('JOIN-9999')).toBeInTheDocument()
  })

  it('ESC로 확인 다이얼로그를 닫는다', () => {
    renderPanel({ invitation: 'issued', invitationCode: 'JOIN-4821' })

    fireEvent.click(screen.getByRole('button', { name: '초대 철회' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('처리 중에는 ESC로 닫지 않고 확인 버튼도 다시 누를 수 없다', () => {
    const { onRevokeInvitation, update } = renderPanel({
      invitation: 'issued',
      invitationCode: 'JOIN-4821',
    })

    fireEvent.click(screen.getByRole('button', { name: '초대 철회' }))
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: '초대 철회' }),
    )
    expect(onRevokeInvitation).toHaveBeenCalledTimes(1)

    /* 컨테이너가 요청을 시작했다고 알려 온 뒤의 상태. */
    update({ pendingAction: { kind: 'revokeInvitation' } })

    const dialog = screen.getByRole('dialog')
    const confirm = within(dialog).getByRole('button', { name: '초대 철회' })
    expect(confirm).toBeDisabled()
    expect(confirm).toHaveAttribute('aria-busy', 'true')

    fireEvent.click(confirm)
    expect(onRevokeInvitation).toHaveBeenCalledTimes(1)

    /* 요청이 이미 서버에 가 있으므로 ESC로 닫아 취소한 것처럼 보이게 하지 않는다. */
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})

describe('일행 패널 - 구성원 제거와 이탈', () => {
  it('대표자의 제거는 대상 이름을 담은 다이얼로그를 거친다', () => {
    const { onRemoveMember } = renderPanel({ currentUserRole: 'REPRESENTATIVE' })

    fireEvent.click(screen.getByRole('button', { name: '일행 2 내보내기' }))

    const dialog = screen.getByRole('dialog', { name: '일행 2 내보내기' })
    fireEvent.click(within(dialog).getByRole('button', { name: '내보내기' }))

    expect(onRemoveMember).toHaveBeenCalledWith('ms-member')
  })

  it('제거 처리 중에는 같은 대상을 다시 제거하지 않는다', () => {
    const { onRemoveMember } = renderPanel({
      currentUserRole: 'REPRESENTATIVE',
      pendingAction: { kind: 'removeMember', membershipId: 'ms-member' },
    })

    const rowButton = screen.getByRole('button', { name: '일행 2 내보내기' })
    expect(rowButton).toBeDisabled()
    expect(rowButton).toHaveAttribute('aria-busy', 'true')

    fireEvent.click(rowButton)
    expect(onRemoveMember).not.toHaveBeenCalled()
  })

  /* 확인 창을 닫아도 실패 사실은 남아야 한다. 사라지면 처리된 것으로 읽힌다. */
  it('이탈 실패는 확인 다이얼로그를 닫은 뒤에도 남는다', () => {
    const { update } = renderPanel({ currentUserRole: 'MEMBER' })

    fireEvent.click(screen.getByRole('button', { name: '웨이팅 일행에서 나가기' }))
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: '나가기' }),
    )

    update({ errorView: { action: 'depart', code: 'REQUEST_FAILED' } })
    expect(
      within(screen.getByRole('dialog')).getByText('요청을 처리하지 못했습니다.'),
    ).toBeInTheDocument()

    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: '취소' }),
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('요청을 처리하지 못했습니다.')).toBeInTheDocument()
  })

  it('구성원 본인 이탈도 확인 다이얼로그를 거친다', () => {
    const { onDepart } = renderPanel({ currentUserRole: 'MEMBER' })

    fireEvent.click(screen.getByRole('button', { name: '웨이팅 일행에서 나가기' }))

    const dialog = screen.getByRole('dialog', { name: '일행에서 나갈까요?' })
    expect(onDepart).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: '나가기' }))
    expect(onDepart).toHaveBeenCalledTimes(1)
  })
})

describe('일행 패널 - 대표자 이전', () => {
  const PENDING_OFFER: WaitingTransferOffer = {
    offerId: 'offer-1',
    targetMembershipId: 'ms-member',
    status: 'PROPOSED',
    proposedAt: '2026-08-20T10:10:00+09:00',
    expiresAt: '2026-08-20T10:15:00+09:00',
  }

  it('대표자가 대상을 골라 제안한다', () => {
    const { onProposeTransfer } = renderPanel({ currentUserRole: 'REPRESENTATIVE' })

    const select = screen.getByLabelText('대표자를 넘길 구성원')
    const propose = screen.getByRole('button', { name: '대표자 이전 제안' })

    /* 대상을 고르기 전에는 제안할 수 없다. */
    expect(propose).toBeDisabled()

    fireEvent.change(select, { target: { value: 'ms-member' } })
    expect(propose).toBeEnabled()

    fireEvent.click(propose)
    expect(onProposeTransfer).toHaveBeenCalledWith('ms-member')
  })

  it('넘길 구성원이 없으면 선택을 열지 않는다', () => {
    renderPanel({
      currentUserRole: 'REPRESENTATIVE',
      memberships: [{ ...REPRESENTATIVE, self: true }],
    })

    expect(screen.queryByLabelText('대표자를 넘길 구성원')).not.toBeInTheDocument()
    expect(screen.getByText(/대표자를 넘길 구성원이 없습니다\./)).toBeInTheDocument()
  })

  /*
   * version이 오르면 일행 구성이 바뀐 것이다. 그 전에 고른 대상을 그대로 두면
   * 이미 빠진 구성원에게 제안을 보내게 된다.
   */
  it('팀 version이 바뀌면 고른 대상 선택을 버린다', () => {
    const { update } = renderPanel({
      currentUserRole: 'REPRESENTATIVE',
      teamVersion: 3,
    })

    fireEvent.change(screen.getByLabelText('대표자를 넘길 구성원'), {
      target: { value: 'ms-member' },
    })
    expect(screen.getByRole('button', { name: '대표자 이전 제안' })).toBeEnabled()

    update({ teamVersion: 4 })

    expect(screen.getByLabelText('대표자를 넘길 구성원')).toHaveValue('')
    expect(screen.getByRole('button', { name: '대표자 이전 제안' })).toBeDisabled()
  })

  it('제안 대기 중에는 대상과 만료 시각을 알리고 새 제안을 열지 않는다', () => {
    renderPanel({ currentUserRole: 'REPRESENTATIVE', transferOffer: PENDING_OFFER })

    expect(screen.getByText('대표자 이전 제안이 진행 중입니다.')).toBeInTheDocument()
    expect(screen.getByText(/일행 2의 응답을 기다리고 있습니다\./)).toBeInTheDocument()
    expect(screen.getByText(/까지 유효합니다\./)).toBeInTheDocument()
    expect(screen.queryByLabelText('대표자를 넘길 구성원')).not.toBeInTheDocument()
  })

  /* 계약은 대상이 수락할 때까지 기존 대표자를 유지한다. 역할 표시를 앞당기지 않는다. */
  it('제안 대기 중에도 기존 대표자를 그대로 표시한다', () => {
    renderPanel({ currentUserRole: 'REPRESENTATIVE', transferOffer: PENDING_OFFER })

    const rows = screen.getAllByRole('listitem')
    expect(within(rows[0]).getByText('대표자')).toBeInTheDocument()
    expect(within(rows[1]).getByText('구성원')).toBeInTheDocument()
    expect(screen.getByText('수락하기 전까지 대표자는 그대로 유지됩니다.')).toBeInTheDocument()
  })

  it('제안자에게 철회 action을 준다', () => {
    const { onRevokeTransfer } = renderPanel({
      currentUserRole: 'REPRESENTATIVE',
      transferOffer: PENDING_OFFER,
    })

    fireEvent.click(screen.getByRole('button', { name: '제안 철회' }))
    expect(onRevokeTransfer).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: '대표자 맡기' })).not.toBeInTheDocument()
  })

  it('제안 대상에게 수락과 거절 action을 준다', () => {
    const { onAcceptTransfer, onRejectTransfer } = renderPanel({
      currentUserRole: 'MEMBER',
      transferOffer: PENDING_OFFER,
    })

    fireEvent.click(screen.getByRole('button', { name: '대표자 맡기' }))
    expect(onAcceptTransfer).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: '거절' }))
    expect(onRejectTransfer).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: '제안 철회' })).not.toBeInTheDocument()
  })

  it('대상이 아닌 구성원에게는 응답 action을 주지 않는다', () => {
    const memberships: WaitingPartyMember[] = [
      REPRESENTATIVE,
      MEMBER,
      { ...MEMBER, membershipId: 'ms-other', self: true },
    ]
    renderPanel({ currentUserRole: 'MEMBER', memberships, transferOffer: PENDING_OFFER })

    expect(screen.queryByRole('button', { name: '대표자 맡기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '거절' })).not.toBeInTheDocument()
    expect(screen.getByText('대표자 이전 제안이 진행 중입니다.')).toBeInTheDocument()
  })

  it('수락 처리 중에는 중복 제출을 막는다', () => {
    const { onAcceptTransfer } = renderPanel({
      currentUserRole: 'MEMBER',
      transferOffer: PENDING_OFFER,
      pendingAction: { kind: 'acceptTransfer' },
    })

    const accept = screen.getByRole('button', { name: '대표자 맡기' })
    expect(accept).toBeDisabled()
    expect(accept).toHaveAttribute('aria-busy', 'true')
    fireEvent.click(accept)
    expect(onAcceptTransfer).not.toHaveBeenCalled()
  })

  it('끝난 제안은 결과를 구분해 알린다', () => {
    const settled: Array<[WaitingTransferOffer['status'], string]> = [
      ['ACCEPTED', '대표자가 바뀌었습니다.'],
      ['REJECTED', '대표자 이전을 거절했습니다.'],
      ['REVOKED', '대표자 이전 제안을 철회했습니다.'],
      ['EXPIRED', '대표자 이전 제안이 만료되었습니다.'],
    ]

    for (const [status, title] of settled) {
      const { unmount } = renderPanel({
        currentUserRole: 'REPRESENTATIVE',
        transferOffer: { ...PENDING_OFFER, status },
      })
      expect(screen.getByText(title)).toBeInTheDocument()
      unmount()
    }
  })
})

describe('일행 패널 - 구성 변경 불가 상태', () => {
  it('호출 이후에는 구성 변경 action을 모두 감추고 이유를 알린다', () => {
    renderPanel({ teamStatus: 'CALLED', currentUserRole: 'REPRESENTATIVE' })

    expect(screen.getByText('지금은 일행 구성을 바꿀 수 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '일행 초대' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /내보내기/ })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('대표자를 넘길 구성원')).not.toBeInTheDocument()
  })

  /*
   * 계약은 제안·수락·거절·철회 모두 `WAITING`에서만 받는다. 응답 버튼을 남겨 두면
   * 눌러도 거절만 돌아온다.
   */
  it('호출 이후에는 대표자 이전 응답 action도 감추고 상태만 남긴다', () => {
    const offer: WaitingTransferOffer = {
      offerId: 'offer-1',
      targetMembershipId: 'ms-member',
      status: 'PROPOSED',
      proposedAt: '2026-08-20T10:10:00+09:00',
      expiresAt: '2026-08-20T10:15:00+09:00',
    }
    const { unmount } = renderPanel({
      teamStatus: 'CALLED',
      currentUserRole: 'MEMBER',
      transferOffer: offer,
    })

    expect(screen.getByText('대표자 이전 제안이 진행 중입니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '대표자 맡기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '거절' })).not.toBeInTheDocument()
    unmount()

    renderPanel({
      teamStatus: 'CALLED',
      currentUserRole: 'REPRESENTATIVE',
      transferOffer: offer,
    })
    expect(screen.queryByRole('button', { name: '제안 철회' })).not.toBeInTheDocument()
  })

  it('종결 상태에서도 목록은 그대로 보여 준다', () => {
    renderPanel({ teamStatus: 'CANCELLED', currentUserRole: 'MEMBER' })

    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(
      screen.queryByRole('button', { name: '웨이팅 일행에서 나가기' }),
    ).not.toBeInTheDocument()
  })
})

describe('일행 패널 - 오류 표시', () => {
  it('일시적 실패에는 재시도를 준다', () => {
    const { onRetry } = renderPanel({
      errorView: { action: 'issueInvitation', code: 'REQUEST_FAILED' },
    })

    expect(screen.getByText('요청을 처리하지 못했습니다.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  /* 상태가 어긋난 실패는 같은 요청을 다시 보내도 같은 실패가 돌아온다. */
  it('버전 충돌에는 재시도를 주지 않는다', () => {
    renderPanel({ errorView: { action: 'issueInvitation', code: 'VERSION_CONFLICT' } })

    expect(screen.getByText('일행 구성이 방금 바뀌었습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('서버가 준 문구가 있으면 그대로 보여 준다', () => {
    renderPanel({
      errorView: {
        action: 'issueInvitation',
        code: 'MUTATION_NOT_ALLOWED',
        message: '현재 웨이팅 상태에서는 일행 구성을 변경할 수 없습니다.',
      },
    })

    expect(
      screen.getByText('현재 웨이팅 상태에서는 일행 구성을 변경할 수 없습니다.'),
    ).toBeInTheDocument()
  })

  it('실패한 조작 자리에만 오류를 붙인다', () => {
    renderPanel({
      currentUserRole: 'REPRESENTATIVE',
      errorView: { action: 'proposeTransfer', code: 'TRANSFER_INVALID' },
    })

    expect(
      screen.getByText('사용할 수 없는 대표자 이전 제안입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('사용할 수 없는 초대입니다.')).not.toBeInTheDocument()
  })
})

describe('일행 패널 - 접근성', () => {
  it('영역과 제목을 프로그램적으로 연결한다', () => {
    renderPanel()

    const section = screen.getByRole('region', { name: '일행' })
    expect(
      within(section).getByRole('heading', { level: 2, name: '일행' }),
    ).toBeInTheDocument()
  })

  it('키보드로 초대 발급을 실행할 수 있다', () => {
    const { onIssueInvitation } = renderPanel()

    const button = screen.getByRole('button', { name: '일행 초대' })
    button.focus()
    expect(button).toHaveFocus()

    fireEvent.click(button)
    expect(onIssueInvitation).toHaveBeenCalledTimes(1)
  })

  it('다이얼로그를 열면 안쪽 요소로 포커스를 옮기고 닫으면 되돌린다', () => {
    renderPanel({ currentUserRole: 'MEMBER' })

    const trigger = screen.getByRole('button', { name: '웨이팅 일행에서 나가기' })
    trigger.focus()
    fireEvent.click(trigger)

    const dialog = screen.getByRole('dialog')
    expect(dialog).toContainElement(document.activeElement as HTMLElement)

    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))
    expect(trigger).toHaveFocus()
  })

  it('대표자를 넘길 대상 선택에 레이블을 붙인다', () => {
    renderPanel({ currentUserRole: 'REPRESENTATIVE' })

    const select = screen.getByLabelText('대표자를 넘길 구성원')
    expect(select.tagName).toBe('SELECT')
  })
})
