import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { createApiClient } from '../../../../shared/api/client'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  acceptWaitingPartyInvitation,
  departWaitingPartyMembership,
  issueWaitingPartyInvitation,
  proposeWaitingRepresentativeTransfer,
  removeWaitingPartyMembership,
  revokeWaitingPartyInvitation,
  revokeWaitingRepresentativeTransfer,
} from './queries'

const TEAM_ID = 'team-482'
const INVITATION_ID = 'invitation-482'
const MEMBERSHIP_ID = 'membership-482'
const OFFER_ID = 'offer-482'
const KEY = '00000000-0000-4000-8000-000000000482'

const invitation = {
  invitationId: INVITATION_ID,
  expiresAt: '2026-08-20T03:15:00+09:00',
  invitationCode: 'JOIN-4821',
}

const snapshot = {
  waitingTeamId: TEAM_ID,
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
      membershipId: 'representative-1',
      role: 'REPRESENTATIVE' as const,
      joinedAt: '2026-08-20T02:00:00+09:00',
      self: true,
    },
  ],
}

const offer = {
  offerId: OFFER_ID,
  targetMembershipId: MEMBERSHIP_ID,
  status: 'PROPOSED' as const,
  proposedAt: '2026-08-20T02:10:00+09:00',
  expiresAt: '2026-08-20T02:15:00+09:00',
}

describe('소비자 웨이팅 일행 API', () => {
  const apiClient = createApiClient()

  it('초대 발급에 snapshot version과 UUID 멱등 키를 전달한다', async () => {
    let body: unknown
    let key: string | null = null

    server.use(
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        async ({ request }) => {
          body = await request.json()
          key = request.headers.get('Idempotency-Key')
          return successResponse(invitation)
        },
      ),
    )

    const result = await issueWaitingPartyInvitation(apiClient, {
      teamId: TEAM_ID,
      expectedVersion: 7,
      idempotencyKey: KEY,
    })

    expect(body).toEqual({ expectedVersion: 7 })
    expect(key).toBe(KEY)
    expect(result).toEqual(invitation)
  })

  it('초대 철회에 발급 응답의 invitationId를 사용한다', async () => {
    let requested = false
    server.use(
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations/${INVITATION_ID}/revocations`,
        async ({ request }) => {
          requested =
            request.headers.get('Idempotency-Key') === KEY &&
            JSON.stringify(await request.json()) ===
              JSON.stringify({ expectedVersion: 8 })
          return successResponse({ ...invitation, invitationCode: null })
        },
      ),
    )

    await revokeWaitingPartyInvitation(apiClient, {
      teamId: TEAM_ID,
      invitationId: INVITATION_ID,
      expectedVersion: 8,
      idempotencyKey: KEY,
    })

    expect(requested).toBe(true)
  })

  it('초대 수락은 코드와 멱등 키만 전달하고 기존 팀 snapshot을 반환한다', async () => {
    let body: unknown
    let key: string | null = null
    server.use(
      http.post(
        '/api/v1/consumers/me/waiting-invitation-acceptances',
        async ({ request }) => {
          body = await request.json()
          key = request.headers.get('Idempotency-Key')
          return successResponse(snapshot)
        },
      ),
    )

    const result = await acceptWaitingPartyInvitation(apiClient, {
      invitationCode: 'JOIN-4821',
      idempotencyKey: KEY,
    })

    expect(body).toEqual({ invitationCode: 'JOIN-4821' })
    expect(key).toBe(KEY)
    expect(result).toEqual(snapshot)
  })

  it('이탈과 제거는 현재 version을 보내고 서버 snapshot을 그대로 반환한다', async () => {
    const requests: Array<{ path: string; body: unknown }> = []
    server.use(
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/membership-departures`,
        async ({ request }) => {
          requests.push({
            path: new URL(request.url).pathname,
            body: await request.json(),
          })
          return successResponse(snapshot)
        },
      ),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/memberships/${MEMBERSHIP_ID}/removals`,
        async ({ request }) => {
          requests.push({
            path: new URL(request.url).pathname,
            body: await request.json(),
          })
          return successResponse(snapshot)
        },
      ),
    )

    await departWaitingPartyMembership(apiClient, {
      teamId: TEAM_ID,
      expectedVersion: 8,
      idempotencyKey: KEY,
    })
    await removeWaitingPartyMembership(apiClient, {
      teamId: TEAM_ID,
      membershipId: MEMBERSHIP_ID,
      expectedVersion: 8,
      idempotencyKey: KEY,
    })

    expect(requests).toEqual([
      {
        path: `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/membership-departures`,
        body: { expectedVersion: 8 },
      },
      {
        path: `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/memberships/${MEMBERSHIP_ID}/removals`,
        body: { expectedVersion: 8 },
      },
    ])
  })

  it('대표자 이전은 제안 생성과 같은 세션의 철회만 연결한다', async () => {
    const bodies: unknown[] = []
    server.use(
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/representative-transfer-offers`,
        async ({ request }) => {
          bodies.push(await request.json())
          return successResponse(offer)
        },
      ),
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/representative-transfer-offers/${OFFER_ID}/revocations`,
        async ({ request }) => {
          bodies.push(await request.json())
          return successResponse({ ...offer, status: 'REVOKED' })
        },
      ),
    )

    expect(
      await proposeWaitingRepresentativeTransfer(apiClient, {
        teamId: TEAM_ID,
        targetMembershipId: MEMBERSHIP_ID,
        expectedVersion: 8,
        idempotencyKey: KEY,
      }),
    ).toEqual(offer)

    expect(
      await revokeWaitingRepresentativeTransfer(apiClient, {
        teamId: TEAM_ID,
        offerId: OFFER_ID,
        expectedVersion: 9,
        idempotencyKey: KEY,
      }),
    ).toEqual({ ...offer, status: 'REVOKED' })
    expect(bodies).toEqual([
      { targetMembershipId: MEMBERSHIP_ID, expectedVersion: 8 },
      { expectedVersion: 9 },
    ])
  })

  it('계약에 없는 응답을 성공으로 처리하지 않는다', async () => {
    server.use(
      http.post(
        `/api/v1/consumers/me/waiting-teams/${TEAM_ID}/invitations`,
        () => HttpResponse.json({ invitationCode: 'leaked' }),
      ),
    )

    await expect(
      issueWaitingPartyInvitation(apiClient, {
        teamId: TEAM_ID,
        expectedVersion: 7,
        idempotencyKey: KEY,
      }),
    ).rejects.toMatchObject({ name: 'ApiContractError' })
  })
})
