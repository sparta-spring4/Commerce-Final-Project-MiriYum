import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { ConsumerAuthProvider } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import { ApiError } from '../../../../shared/api/apiError'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import { consumerWaitingKeys, type ConsumerWaitingSnapshot } from '../api/queries'
import { toWaitingPartyError } from '../model/errors'
import { WaitingPartyPanelContainer } from './WaitingPartyPanelContainer'

const TEAM_ID = 'team-482'
const INVITATION_ID = 'invitation-482'
const MEMBER_ID = 'member-482'
const OFFER_ID = 'offer-482'

function representativeSnapshot(
  overrides: Partial<ConsumerWaitingSnapshot> = {},
): ConsumerWaitingSnapshot {
  return {
    waitingTeamId: TEAM_ID,
    storeId: 'store-1',
    businessDate: '2026-08-20',
    status: 'WAITING',
    queueSequence: 12,
    teamsAhead: 3,
    partySize: 3,
    createdAt: '2026-08-20T02:00:00+09:00',
    calledAt: null,
    arrivalDeadline: null,
    arrivedAt: null,
    cancelledAt: null,
    version: 7,
    memberships: [
      {
        membershipId: 'representative-482',
        role: 'REPRESENTATIVE',
        joinedAt: '2026-08-20T02:00:00+09:00',
        self: true,
      },
      {
        membershipId: MEMBER_ID,
        role: 'MEMBER',
        joinedAt: '2026-08-20T02:05:00+09:00',
        self: false,
      },
    ],
    ...overrides,
  }
}

function memberSnapshot(): ConsumerWaitingSnapshot {
  const snapshot = representativeSnapshot()
  return {
    ...snapshot,
    memberships: snapshot.memberships.map((membership) => ({
      ...membership,
      self: membership.membershipId === MEMBER_ID,
    })),
  }
}

function renderPanel(snapshot: ConsumerWaitingSnapshot) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const view = (next: ConsumerWaitingSnapshot) => (
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <WaitingPartyPanelContainer snapshot={next} />
      </ConsumerAuthProvider>
    </QueryClientProvider>
  )
  const result = render(view(snapshot))
  return {
    queryClient,
    rerenderSnapshot(next: ConsumerWaitingSnapshot) {
      result.rerender(view(next))
    },
    unmount: result.unmount,
  }
}

const invitation = {
  invitationId: INVITATION_ID,
  expiresAt: '2026-08-20T03:15:00+09:00',
  invitationCode: 'JOIN-4821',
}

describe('웨이팅 일행 패널 API 컨테이너', () => {
  it('초대 발급 결과를 짧은 수명 상태로 표시하고 사용자의 복사 action에서만 복사한다', async () => {
    let requestBody: unknown
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        async ({ request }) => {
          requestBody = await request.json()
          return successResponse(invitation)
        },
      ),
    )
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })

    renderPanel(representativeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: '일행 초대' }))

    expect(await screen.findByText('JOIN-4821')).toBeInTheDocument()
    expect(requestBody).toEqual({ expectedVersion: 7 })
    expect(writeText).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '코드 복사' }))
    expect(await screen.findByText('초대 코드를 복사했습니다.')).toBeInTheDocument()
    expect(writeText).toHaveBeenCalledWith('JOIN-4821')
    expect(JSON.stringify(localStorage)).not.toContain('JOIN-4821')
    expect(JSON.stringify(sessionStorage)).not.toContain('JOIN-4821')
  })

  it('멱등 replay에서 코드가 null이면 원문을 추측하지 않는다', async () => {
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        () => successResponse({ ...invitation, invitationCode: null }),
      ),
    )

    renderPanel(representativeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: '일행 초대' }))

    expect(
      await screen.findByText(/초대 코드는 처음 발급할 때만 볼 수 있습니다/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '코드 복사' })).not.toBeInTheDocument()
  })

  it('실패한 초대 발급 재시도에는 같은 Idempotency-Key를 사용한다', async () => {
    const keys: string[] = []
    let attempts = 0
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        ({ request }) => {
          attempts += 1
          keys.push(request.headers.get('Idempotency-Key') ?? '')
          if (attempts === 1) return errorResponse(500, 'COMMON_011', '실패')
          return successResponse(invitation)
        },
      ),
    )

    renderPanel(representativeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: '일행 초대' }))
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))

    await waitFor(() => expect(attempts).toBe(2))
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(new Set(keys).size).toBe(1)
  })

  it('발급한 같은 화면 세션에서만 invitationId로 철회한다', async () => {
    const requestedPaths: string[] = []
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        ({ request }) => {
          requestedPaths.push(new URL(request.url).pathname)
          return successResponse(invitation)
        },
      ),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations/${INVITATION_ID}/revocations`,
        ({ request }) => {
          requestedPaths.push(new URL(request.url).pathname)
          return successResponse({ ...invitation, invitationCode: null })
        },
      ),
    )

    const { rerenderSnapshot, unmount } = renderPanel(representativeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: '일행 초대' }))
    await screen.findByText('JOIN-4821')
    rerenderSnapshot(representativeSnapshot({ version: 8 }))
    fireEvent.click(screen.getByRole('button', { name: '초대 철회' }))
    fireEvent.click(
      within(screen.getByRole('dialog', { name: '초대를 철회할까요?' })).getByRole(
        'button',
        { name: '초대 철회' },
      ),
    )

    expect(await screen.findByText('초대를 철회했습니다.')).toBeInTheDocument()
    expect(requestedPaths).toEqual([
      `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
      `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations/${INVITATION_ID}/revocations`,
    ])

    unmount()
    renderPanel(representativeSnapshot({ version: 9 }))
    expect(screen.queryByRole('button', { name: '초대 철회' })).not.toBeInTheDocument()
  })

  it('다른 웨이팅 팀으로 전환하면 이전 팀의 초대 원문과 식별자를 즉시 버린다', async () => {
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        () => successResponse(invitation),
      ),
    )

    const { rerenderSnapshot } = renderPanel(representativeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: '일행 초대' }))
    expect(await screen.findByText('JOIN-4821')).toBeInTheDocument()

    rerenderSnapshot(
      representativeSnapshot({ waitingTeamId: 'team-483', version: 1 }),
    )

    expect(screen.queryByText('JOIN-4821')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '초대 철회' })).not.toBeInTheDocument()
  })

  it('이전 팀에서 늦게 도착한 초대 응답을 새 팀 화면에 반영하지 않는다', async () => {
    let markRequestStarted!: () => void
    let releaseResponse!: () => void
    const requestStarted = new Promise<void>((resolve) => {
      markRequestStarted = resolve
    })
    const responseGate = new Promise<void>((resolve) => {
      releaseResponse = resolve
    })
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        async () => {
          markRequestStarted()
          await responseGate
          return successResponse(invitation)
        },
      ),
    )

    const { rerenderSnapshot } = renderPanel(representativeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: '일행 초대' }))
    await requestStarted
    rerenderSnapshot(
      representativeSnapshot({ waitingTeamId: 'team-483', version: 1 }),
    )
    releaseResponse()

    await waitFor(() => expect(screen.queryByText('JOIN-4821')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '초대 철회' })).not.toBeInTheDocument()
  })

  it('명령 실행 중에는 다른 섹션의 versioned 명령을 시작하지 않는다', async () => {
    let markRequestStarted!: () => void
    let releaseResponse!: () => void
    const invitationKeys: string[] = []
    let invitationRequests = 0
    let proposalRequests = 0
    const requestStarted = new Promise<void>((resolve) => {
      markRequestStarted = resolve
    })
    const responseGate = new Promise<void>((resolve) => {
      releaseResponse = resolve
    })
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        async ({ request }) => {
          invitationRequests += 1
          invitationKeys.push(request.headers.get('Idempotency-Key') ?? '')
          if (invitationRequests === 1) {
            markRequestStarted()
            await responseGate
            return errorResponse(500, 'COMMON_011', '실패')
          }
          return successResponse(invitation)
        },
      ),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/representative-transfer-offers`,
        () => {
          proposalRequests += 1
          return successResponse({
            offerId: OFFER_ID,
            targetMembershipId: MEMBER_ID,
            status: 'PROPOSED',
            proposedAt: '2026-08-20T02:10:00+09:00',
            expiresAt: '2026-08-20T02:15:00+09:00',
          })
        },
      ),
    )

    renderPanel(representativeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: '일행 초대' }))
    await requestStarted
    fireEvent.change(screen.getByLabelText('대표자를 넘길 구성원'), {
      target: { value: MEMBER_ID },
    })
    fireEvent.click(screen.getByRole('button', { name: '대표자 이전 제안' }))

    releaseResponse()
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('JOIN-4821')).toBeInTheDocument()
    expect(invitationRequests).toBe(2)
    expect(new Set(invitationKeys).size).toBe(1)
    expect(proposalRequests).toBe(0)
  })

  it('대표자의 제거는 응답 snapshot을 반영하고 구성원 이탈은 current query를 비운다', async () => {
    const afterRemoval = representativeSnapshot({
      version: 8,
      memberships: [representativeSnapshot().memberships[0]],
    })
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/memberships/${MEMBER_ID}/removals`,
        () => successResponse(afterRemoval),
      ),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/membership-departures`,
        () => successResponse(representativeSnapshot({ version: 8 })),
      ),
    )

    const representative = renderPanel(representativeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: '일행 2 내보내기' }))
    fireEvent.click(
      within(screen.getByRole('dialog', { name: '일행 2 내보내기' })).getByRole(
        'button',
        { name: '내보내기' },
      ),
    )
    await waitFor(() =>
      expect(representative.queryClient.getQueryData(consumerWaitingKeys.current)).toEqual(
        afterRemoval,
      ),
    )
    representative.unmount()

    const member = renderPanel(memberSnapshot())
    member.queryClient.setQueryData(consumerWaitingKeys.current, memberSnapshot())
    fireEvent.click(
      screen.getByRole('button', { name: '웨이팅 일행에서 나가기' }),
    )
    fireEvent.click(
      within(screen.getByRole('dialog', { name: '일행에서 나갈까요?' })).getByRole(
        'button',
        { name: '나가기' },
      ),
    )
    await waitFor(() =>
      expect(member.queryClient.getQueryData(consumerWaitingKeys.current)).toBeNull(),
    )
  })

  it('WAITING_005는 version 충돌 안내로 분기하고 중앙 query를 무효화한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        () => errorResponse(409, 'WAITING_005', '서버 문구'),
      ),
    )
    const { queryClient } = renderPanel(representativeSnapshot())
    queryClient.setQueryData(consumerWaitingKeys.current, representativeSnapshot())

    fireEvent.click(screen.getByRole('button', { name: '일행 초대' }))

    expect(
      await screen.findByText('일행 구성이 방금 바뀌었습니다.'),
    ).toBeInTheDocument()
    expect(
      queryClient.getQueryState(consumerWaitingKeys.current)?.isInvalidated,
    ).toBe(true)
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('대표자 이전은 제안 응답을 가진 같은 세션의 철회까지만 호출한다', async () => {
    const paths: string[] = []
    const offer = {
      offerId: OFFER_ID,
      targetMembershipId: MEMBER_ID,
      status: 'PROPOSED' as const,
      proposedAt: '2026-08-20T02:10:00+09:00',
      expiresAt: '2026-08-20T02:15:00+09:00',
    }
    server.use(
      authenticatedConsumer(),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/representative-transfer-offers`,
        ({ request }) => {
          paths.push(new URL(request.url).pathname)
          return successResponse(offer)
        },
      ),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/representative-transfer-offers/${OFFER_ID}/revocations`,
        ({ request }) => {
          paths.push(new URL(request.url).pathname)
          return successResponse({ ...offer, status: 'REVOKED' })
        },
      ),
    )

    const { rerenderSnapshot } = renderPanel(representativeSnapshot())
    fireEvent.change(screen.getByLabelText('대표자를 넘길 구성원'), {
      target: { value: MEMBER_ID },
    })
    fireEvent.click(screen.getByRole('button', { name: '대표자 이전 제안' }))
    expect(
      await screen.findByText('대표자 이전 제안이 진행 중입니다.'),
    ).toBeInTheDocument()
    rerenderSnapshot(representativeSnapshot({ version: 8 }))
    fireEvent.click(screen.getByRole('button', { name: '제안 철회' }))

    expect(await screen.findByText('대표자 이전 제안을 철회했습니다.')).toBeInTheDocument()
    expect(paths).toEqual([
      `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/representative-transfer-offers`,
      `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/representative-transfer-offers/${OFFER_ID}/revocations`,
    ])
  })
})

describe('웨이팅 일행 오류 code 매핑', () => {
  it.each([
    ['WAITING_003', 'NOT_FOUND'],
    ['WAITING_005', 'VERSION_CONFLICT'],
    ['WAITING_014', 'INVITATION_INVALID'],
    ['WAITING_015', 'MUTATION_NOT_ALLOWED'],
    ['WAITING_016', 'TRANSFER_INVALID'],
    ['WAITING_017', 'CAPACITY_EXCEEDED'],
  ] as const)('%s를 서버 message와 무관한 view code로 옮긴다', (code, expected) => {
    expect(
      toWaitingPartyError(
        'issueInvitation',
        new ApiError({ status: code === 'WAITING_003' ? 404 : 409, code, message: '무시' }),
      ),
    ).toEqual({ action: 'issueInvitation', code: expected })
  })
})
