import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { QueryClient } from '@tanstack/react-query'
import { http } from 'msw'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ConsumerAuthProvider } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import { TestQueryProvider } from '../../../../test/TestQueryProvider'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import { consumerWaitingKeys } from '../api/queries'
import { WaitingInvitationAcceptRoute } from './WaitingInvitationAcceptRoute'

const ACCEPT_PATH = '/api/v1/consumers/me/waiting-invitation-acceptances'
const ROUTE_PATH = '/waiting/invitations/accept'

const joinedSnapshot = {
  waitingTeamId: 'team-482',
  storeId: 'store-1',
  businessDate: '2026-08-20',
  status: 'WAITING' as const,
  queueSequence: 12,
  teamsAhead: 3,
  partySize: 3,
  createdAt: '2026-08-20T02:00:00+09:00',
  calledAt: null,
  arrivalDeadline: null,
  arrivedAt: null,
  cancelledAt: null,
  version: 8,
  memberships: [
    {
      membershipId: 'member-482',
      role: 'MEMBER' as const,
      joinedAt: '2026-08-20T02:10:00+09:00',
      self: true,
    },
  ],
}

function renderRoute() {
  const captured: { queryClient?: QueryClient } = {}
  render(
    <TestQueryProvider
      onReady={(queryClient) => {
        captured.queryClient = queryClient
      }}
    >
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[ROUTE_PATH]}>
          <WaitingInvitationAcceptRoute />
        </MemoryRouter>
      </ConsumerAuthProvider>
    </TestQueryProvider>,
  )
  if (captured.queryClient === undefined) {
    throw new Error('QueryClient가 준비되지 않았습니다.')
  }
  return captured.queryClient
}

function submitCode(code: string) {
  fireEvent.change(screen.getByLabelText('초대 코드'), {
    target: { value: code },
  })
  fireEvent.click(screen.getByRole('button', { name: '웨이팅 일행으로 합류' }))
}

describe('웨이팅 일행 합류 route', () => {
  it('trim한 코드와 UUID 키를 보내고 성공 snapshot을 중앙 query에 반영한다', async () => {
    let body: unknown
    let key: string | null = null
    server.use(
      authenticatedConsumer(),
      http.post(ACCEPT_PATH, async ({ request }) => {
        body = await request.json()
        key = request.headers.get('Idempotency-Key')
        return successResponse(joinedSnapshot)
      }),
    )
    localStorage.setItem('unrelated', 'keep')
    sessionStorage.setItem('unrelated', 'keep')

    const queryClient = renderRoute()
    submitCode('  JOIN-4821  ')

    expect(
      within(await screen.findByRole('status')).getByText(
        '일행으로 합류했습니다.',
      ),
    ).toBeInTheDocument()
    expect(body).toEqual({ invitationCode: 'JOIN-4821' })
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    expect(queryClient.getQueryData(consumerWaitingKeys.current)).toEqual(
      joinedSnapshot,
    )
    expect(window.location.search).toBe('')
    expect(JSON.stringify(localStorage)).not.toContain('JOIN-4821')
    expect(JSON.stringify(sessionStorage)).not.toContain('JOIN-4821')
    expect(localStorage.getItem('unrelated')).toBe('keep')
  })

  it('같은 코드 재시도는 같은 Idempotency-Key를 유지한다', async () => {
    const keys: string[] = []
    let attempts = 0
    server.use(
      authenticatedConsumer(),
      http.post(ACCEPT_PATH, ({ request }) => {
        attempts += 1
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        if (attempts === 1) {
          return errorResponse(500, 'COMMON_011', '서버 오류입니다.')
        }
        return successResponse(joinedSnapshot)
      }),
    )

    renderRoute()
    submitCode('JOIN-4821')
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))

    await waitFor(() => expect(attempts).toBe(2))
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(new Set(keys).size).toBe(1)
  })

  it('현재 팀 상태 충돌은 중앙 웨이팅 query를 무효화한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.post(ACCEPT_PATH, () =>
        errorResponse(409, 'WAITING_015', '현재 상태에서는 합류할 수 없습니다.'),
      ),
    )
    const queryClient = renderRoute()
    queryClient.setQueryData(consumerWaitingKeys.current, joinedSnapshot)

    submitCode('JOIN-4821')

    await screen.findByText('지금은 이 일행에 합류할 수 없습니다.')
    expect(
      queryClient.getQueryState(consumerWaitingKeys.current)?.isInvalidated,
    ).toBe(true)
  })

  it.each([
    ['WAITING_011', '이미 진행 중인 웨이팅이 있습니다.'],
    ['WAITING_014', '사용할 수 없는 초대 코드입니다.'],
    ['WAITING_015', '지금은 이 일행에 합류할 수 없습니다.'],
    ['WAITING_017', '일행 인원이 가득 찼습니다.'],
  ])('%s를 서버 message가 아닌 확정 안내로 분기한다', async (code, title) => {
    server.use(
      authenticatedConsumer(),
      http.post(ACCEPT_PATH, () =>
        errorResponse(409, code, '화면 분기에 사용하면 안 되는 서버 문구'),
      ),
    )

    renderRoute()
    submitCode('JOIN-4821')

    expect(
      within(await screen.findByRole('alert')).getByText(title),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('화면 분기에 사용하면 안 되는 서버 문구'),
    ).not.toBeInTheDocument()
  })
})
