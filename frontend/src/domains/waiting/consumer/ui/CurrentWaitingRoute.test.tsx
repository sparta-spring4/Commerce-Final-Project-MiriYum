import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { StrictMode } from 'react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ConsumerAuthProvider } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import { consumerWaitingKeys, type ConsumerWaitingSnapshot } from '../api/queries'
import { CurrentWaitingRoute } from './CurrentWaitingRoute'

const CURRENT_PATH = '/api/v1/consumers/me/waiting-teams/current'
const CANCEL_PATH =
  '/api/v1/consumers/me/waiting-teams/:waitingTeamId/cancellations'
const TEAM_ID = 'team-410'

function snapshot(
  overrides: Partial<ConsumerWaitingSnapshot> = {},
): ConsumerWaitingSnapshot {
  return {
    waitingTeamId: TEAM_ID,
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

function renderRoute({ strict = false } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  const tree = (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/waiting/current']}>
        <ConsumerAuthProvider>
          <CurrentWaitingRoute />
        </ConsumerAuthProvider>
      </MemoryRouter>
    </QueryClientProvider>
  )

  const view = render(strict ? <StrictMode>{tree}</StrictMode> : tree)

  return { ...view, queryClient }
}

async function cancelWaiting() {
  fireEvent.click(
    await screen.findByRole('button', { name: '웨이팅 취소하기' }),
  )
  const dialog = screen.getByRole('dialog', { name: '웨이팅을 취소할까요?' })
  fireEvent.click(within(dialog).getByRole('button', { name: '취소 확정' }))
}

describe('현재 웨이팅 조회', () => {
  it('중앙 snapshot을 조회해 순번과 앞 팀 수를 보여 준다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () => successResponse(snapshot())),
    )

    renderRoute()

    expect(await screen.findByText('12번')).toBeInTheDocument()
    expect(screen.getByText('3팀')).toBeInTheDocument()
    expect(screen.getByText('대기 중')).toBeInTheDocument()
  })

  it('일행 패널을 함께 붙여 일행 목록을 보여 준다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () =>
        successResponse(
          snapshot({
            memberships: [
              {
                membershipId: 'membership-1',
                role: 'REPRESENTATIVE',
                joinedAt: '2026-08-20T02:00:00+09:00',
                self: true,
              },
              {
                membershipId: 'membership-2',
                role: 'MEMBER',
                joinedAt: '2026-08-20T02:20:00+09:00',
                self: false,
              },
            ],
          }),
        ),
      ),
    )

    renderRoute()

    expect(await screen.findByText('12번')).toBeInTheDocument()
    expect(
      screen.getByRole('region', { name: /일행/ }),
    ).toBeInTheDocument()
  })

  it('활성 웨이팅이 없는 404는 오류가 아니라 빈 상태로 보여 준다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () =>
        errorResponse(404, 'WAITING_003', '웨이팅 팀을 찾을 수 없습니다.'),
      ),
    )

    renderRoute()

    expect(await screen.findByText('현재 웨이팅이 없습니다.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '매장 찾기' })).toHaveAttribute(
      'href',
      '/stores',
    )
  })

  it('조회 실패는 재시도를 제공한다', async () => {
    let attempts = 0
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () => {
        attempts += 1
        if (attempts === 1) {
          return errorResponse(500, 'COMMON_009', '일시적인 오류입니다.')
        }
        return successResponse(snapshot())
      }),
    )

    renderRoute()

    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('12번')).toBeInTheDocument()
  })

  it('계정 상태가 막힌 403은 재시도 대신 권한 안내로 보여 준다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () =>
        errorResponse(403, 'AUTH_011', '현재 계정 상태로는 이용할 수 없습니다.'),
      ),
    )

    renderRoute()

    expect(
      await screen.findByText('현재 웨이팅을 불러오지 못했습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '다시 시도' }),
    ).not.toBeInTheDocument()
  })
})

describe('웨이팅 취소', () => {
  it('expectedVersion과 멱등 키를 담아 취소를 보낸다', async () => {
    const sent: { body: unknown; key: string | null }[] = []
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () => successResponse(snapshot())),
      http.post(CANCEL_PATH, async ({ request, params }) => {
        expect(params.waitingTeamId).toBe(TEAM_ID)
        sent.push({
          body: await request.json(),
          key: request.headers.get('Idempotency-Key'),
        })
        return successResponse(
          snapshot({ status: 'CANCELLED', version: 10, memberships: [] }),
        )
      }),
    )

    renderRoute()
    await cancelWaiting()

    expect(
      await screen.findByText('웨이팅을 취소했습니다.'),
    ).toBeInTheDocument()
    expect(sent).toHaveLength(1)
    expect(sent[0].body).toEqual({ expectedVersion: 9 })
    expect(sent[0].key).toBeTruthy()
  })

  it('취소 성공은 중앙 캐시를 비워 마이페이지 카드까지 수렴시킨다', async () => {
    /* 취소되면 활성 membership이 사라져 서버 조회가 다시 404가 된다. */
    let cancelled = false
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () =>
        cancelled
          ? errorResponse(404, 'WAITING_003', '웨이팅 팀을 찾을 수 없습니다.')
          : successResponse(snapshot()),
      ),
      http.post(CANCEL_PATH, () => {
        cancelled = true
        return successResponse(
          snapshot({ status: 'CANCELLED', version: 10, memberships: [] }),
        )
      }),
    )

    const { queryClient } = renderRoute()
    await cancelWaiting()

    await screen.findByText('웨이팅을 취소했습니다.')
    await waitFor(() => {
      expect(queryClient.getQueryData(consumerWaitingKeys.current)).toBeNull()
    })
  })

  /*
   * StrictMode는 mount→unmount→mount로 effect를 두 번 돌린다. 살아 있는지
   * 표시하는 ref를 cleanup에서만 끄면 두 번째 mount가 꺼진 값을 물려받아
   * 응답을 조용히 버린다. 앱은 StrictMode로 렌더되므로 여기서 고정한다.
   */
  it('StrictMode 재mount 뒤에도 취소 결과를 반영한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () => successResponse(snapshot())),
      http.post(CANCEL_PATH, () =>
        successResponse(
          snapshot({ status: 'CANCELLED', version: 10, memberships: [] }),
        ),
      ),
    )

    renderRoute({ strict: true })
    await cancelWaiting()

    expect(
      await screen.findByText('웨이팅을 취소했습니다.'),
    ).toBeInTheDocument()
  })

  it('버전 충돌은 재시도 대신 최신 상태 확인으로 보낸다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () => successResponse(snapshot())),
      http.post(CANCEL_PATH, () =>
        errorResponse(
          409,
          'WAITING_005',
          '웨이팅 팀이 변경되었습니다. 최신 상태를 다시 확인해 주세요.',
        ),
      ),
    )

    renderRoute()
    await cancelWaiting()

    expect(
      await screen.findByText(
        '웨이팅 팀이 변경되었습니다. 최신 상태를 다시 확인해 주세요.',
      ),
    ).toBeInTheDocument()
    const alert = screen.getByRole('alert')
    expect(
      within(alert).getByRole('button', { name: '최신 상태 확인' }),
    ).toBeInTheDocument()
  })

  it('결과 불명 뒤에는 성공으로 표시하지 않는다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () => successResponse(snapshot())),
      http.post(CANCEL_PATH, () =>
        errorResponse(503, 'COMMON_009', '일시적인 오류입니다.'),
      ),
    )

    renderRoute()
    await cancelWaiting()

    expect(
      await screen.findByText('취소 처리 여부를 확인하지 못했습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('웨이팅을 취소했습니다.'),
    ).not.toBeInTheDocument()
  })

  it('같은 버전으로 다시 시도하면 같은 멱등 키를 쓴다', async () => {
    const keys: (string | null)[] = []
    let attempts = 0
    server.use(
      authenticatedConsumer(),
      http.get(CURRENT_PATH, () => successResponse(snapshot())),
      http.post(CANCEL_PATH, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        attempts += 1
        if (attempts === 1) {
          return errorResponse(400, 'COMMON_001', '잘못된 요청입니다.')
        }
        return successResponse(
          snapshot({ status: 'CANCELLED', version: 10, memberships: [] }),
        )
      }),
    )

    renderRoute()
    await cancelWaiting()

    const alert = await screen.findByRole('alert')
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }))

    await screen.findByText('웨이팅을 취소했습니다.')
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
  })
})
