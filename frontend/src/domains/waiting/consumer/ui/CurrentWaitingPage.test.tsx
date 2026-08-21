import { fireEvent, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../../test/renderWithProviders'
import type { ConsumerWaitingSnapshot } from '../api/queries'
import type { WaitingCancelErrorView } from '../model/currentWaitingView'
import { CurrentWaitingPage, type WaitingCancelPhase } from './CurrentWaitingPage'

const FETCHED_AT = new Date('2026-08-20T03:00:00+09:00').getTime()

function snapshot(
  overrides: Partial<ConsumerWaitingSnapshot> = {},
): ConsumerWaitingSnapshot {
  return {
    waitingTeamId: 'team-410',
    storeId: 'store-77',
    businessDate: '2026-08-20',
    status: 'WAITING',
    queueSequence: 12,
    teamsAhead: 3,
    partySize: 4,
    createdAt: '2026-08-20T02:00:00+09:00',
    calledAt: null,
    arrivalDeadline: null,
    arrivedAt: null,
    cancelledAt: null,
    version: 9,
    memberships: [
      {
        membershipId: 'membership-1',
        role: 'REPRESENTATIVE',
        joinedAt: '2026-08-20T02:00:00+09:00',
        self: true,
      },
    ],
    ...overrides,
  }
}

function asMember(): ConsumerWaitingSnapshot {
  return snapshot({
    memberships: [
      {
        membershipId: 'membership-1',
        role: 'REPRESENTATIVE',
        joinedAt: '2026-08-20T02:00:00+09:00',
        self: false,
      },
      {
        membershipId: 'membership-2',
        role: 'MEMBER',
        joinedAt: '2026-08-20T02:20:00+09:00',
        self: true,
      },
    ],
  })
}

interface Overrides {
  snapshot?: ConsumerWaitingSnapshot
  refreshing?: boolean
  cancelPhase?: WaitingCancelPhase
  cancelError?: WaitingCancelErrorView | null
}

function renderPage(overrides: Overrides = {}) {
  const handlers = {
    onRefresh: vi.fn(),
    onRequestCancel: vi.fn(),
    onConfirmCancel: vi.fn(),
    onDismissCancel: vi.fn(),
  }

  const view = renderWithProviders(
    <CurrentWaitingPage
      snapshot={overrides.snapshot ?? snapshot()}
      fetchedAt={FETCHED_AT}
      refreshing={overrides.refreshing ?? false}
      cancelPhase={overrides.cancelPhase ?? 'idle'}
      cancelError={overrides.cancelError ?? null}
      partyPanel={<p>일행 패널 자리</p>}
      {...handlers}
    />,
  )

  return { ...view, ...handlers }
}

describe('현재 웨이팅 상세 표시', () => {
  it('순번·앞 팀 수·방문 인원을 서버 값 그대로 보여 준다', () => {
    renderPage()

    const queue = screen.getByRole('region', { name: '대기 현황' })
    expect(within(queue).getByText('12번')).toBeInTheDocument()
    expect(within(queue).getByText('3팀')).toBeInTheDocument()
    expect(within(queue).getByText('4명')).toBeInTheDocument()
  })

  it('상태 문구와 상태별 안내를 함께 보여 준다', () => {
    renderPage({ snapshot: snapshot({ status: 'CALLED' }) })

    expect(screen.getByText('입장 호출')).toBeInTheDocument()
    expect(
      screen.getByText(/도착 제한 시각까지 매장에 도착해 주세요/),
    ).toBeInTheDocument()
  })

  it('값이 있는 시각만 줄로 만든다', () => {
    renderPage({
      snapshot: snapshot({
        status: 'CALLED',
        calledAt: '2026-08-20T02:40:00+09:00',
        arrivalDeadline: '2026-08-20T02:55:00+09:00',
      }),
    })

    expect(screen.getByText('호출 시각')).toBeInTheDocument()
    expect(screen.getByText('도착 제한 시각')).toBeInTheDocument()
    expect(screen.queryByText('도착 확인 시각')).not.toBeInTheDocument()
    expect(screen.queryByText('취소 시각')).not.toBeInTheDocument()
  })

  it('종료된 웨이팅에서는 등록 당시 순번을 현재 값처럼 두지 않는다', () => {
    renderPage({
      snapshot: snapshot({
        status: 'CHECKED_IN',
        queueSequence: 12,
        teamsAhead: 0,
      }),
    })

    expect(screen.queryByText('내 순번')).not.toBeInTheDocument()
    expect(screen.queryByText('12번')).not.toBeInTheDocument()
    expect(screen.getByText(/종료된 웨이팅입니다/)).toBeInTheDocument()
    // 인원은 등록 사실이라 남긴다.
    expect(screen.getByText('4명')).toBeInTheDocument()
  })

  it('매장 상세로 가는 링크를 준다', () => {
    renderPage()

    expect(screen.getByRole('link', { name: /매장 정보 보기/ })).toHaveAttribute(
      'href',
      '/stores/store-77',
    )
  })

  it('일행 패널을 화면 안에 끼운다', () => {
    renderPage()

    expect(screen.getByText('일행 패널 자리')).toBeInTheDocument()
  })
})

describe('상태 재조회', () => {
  it('마지막 갱신 시각을 알리고 다시 확인을 요청한다', () => {
    const { onRefresh } = renderPage()

    expect(screen.getByText(/마지막 갱신/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '상태 다시 확인' }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it('재조회 중에는 버튼을 잠근다', () => {
    renderPage({ refreshing: true })

    expect(screen.getByRole('button', { name: /상태 다시 확인/ })).toBeDisabled()
  })
})

describe('웨이팅 취소', () => {
  it('대표자에게만 취소를 노출한다', () => {
    renderPage()

    expect(
      screen.getByRole('button', { name: '웨이팅 취소하기' }),
    ).toBeInTheDocument()
  })

  it('초대로 합류한 일행에게는 취소를 노출하지 않는다', () => {
    renderPage({ snapshot: asMember() })

    expect(
      screen.queryByRole('button', { name: '웨이팅 취소하기' }),
    ).not.toBeInTheDocument()
  })

  it('취소 전이가 불가능한 상태에서는 취소를 노출하지 않는다', () => {
    renderPage({ snapshot: snapshot({ status: 'NO_SHOW' }) })

    expect(
      screen.queryByRole('button', { name: '웨이팅 취소하기' }),
    ).not.toBeInTheDocument()
  })

  it('바로 보내지 않고 확인 단계를 거친다', () => {
    const { onRequestCancel, onConfirmCancel } = renderPage()

    fireEvent.click(screen.getByRole('button', { name: '웨이팅 취소하기' }))

    expect(onRequestCancel).toHaveBeenCalledTimes(1)
    expect(onConfirmCancel).not.toHaveBeenCalled()
  })

  it('확인 단계에서 취소를 확정한다', () => {
    const { onConfirmCancel } = renderPage({ cancelPhase: 'confirming' })

    const dialog = screen.getByRole('dialog', { name: '웨이팅을 취소할까요?' })
    expect(within(dialog).getByText('12번 순번이 사라집니다.')).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: '취소 확정' }))
    expect(onConfirmCancel).toHaveBeenCalledTimes(1)
  })

  it('확인 단계에서 물러날 수 있다', () => {
    const { onDismissCancel } = renderPage({ cancelPhase: 'confirming' })

    const dialog = screen.getByRole('dialog', { name: '웨이팅을 취소할까요?' })
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))
    expect(onDismissCancel).toHaveBeenCalledTimes(1)
  })

  it('보내는 중에는 확인과 물러나기를 모두 잠근다', () => {
    renderPage({ cancelPhase: 'submitting' })

    const dialog = screen.getByRole('dialog', { name: '웨이팅을 취소할까요?' })
    expect(within(dialog).getByRole('button', { name: /취소 확정/ })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: '취소' })).toBeDisabled()
  })

  it('상태가 어긋난 실패는 재시도가 아니라 최신 상태 확인으로 보낸다', () => {
    const { onRefresh } = renderPage({
      cancelError: { code: 'VERSION_CONFLICT' },
    })

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('웨이팅 상태가 방금 바뀌었습니다.')

    fireEvent.click(within(alert).getByRole('button', { name: '최신 상태 확인' }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it('결과 불명은 재시도를 권하지 않고 최신 상태 확인만 남긴다', () => {
    renderPage({ cancelError: { code: 'OUTCOME_UNKNOWN' } })

    expect(
      screen.getByText('취소 처리 여부를 확인하지 못했습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/요청이 서버에 반영됐을 수 있습니다/),
    ).toBeInTheDocument()
  })

  it('재시도 가능한 실패는 같은 취소 명령을 다시 보낸다', () => {
    const { onConfirmCancel, onRefresh } = renderPage({
      cancelError: { code: 'REQUEST_FAILED' },
    })

    const alert = screen.getByRole('alert')
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }))

    expect(onConfirmCancel).toHaveBeenCalledTimes(1)
    expect(onRefresh).not.toHaveBeenCalled()
  })

  it('서버가 준 문구가 있으면 제목에 그대로 쓴다', () => {
    renderPage({
      cancelError: {
        code: 'INVALID_TRANSITION',
        message: '현재 상태에서는 요청한 전이를 수행할 수 없습니다.',
      },
    })

    expect(
      screen.getByText('현재 상태에서는 요청한 전이를 수행할 수 없습니다.'),
    ).toBeInTheDocument()
  })
})
