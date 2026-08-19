import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../../../shared/api/apiError'
import type { ApiClient } from '../../../../shared/api/client'
import type { components } from '../../../../shared/api/generated/waiting'
import { CONSUMER_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/consumer/querySession'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'

export type ConsumerWaitingSnapshot = components['schemas']['WaitingConsumerSnapshot']
export type WaitingInvitation = components['schemas']['WaitingInvitation']
export type WaitingTransferOffer = components['schemas']['WaitingTransferOffer']

interface IdempotentCommand {
  idempotencyKey: string
}

interface VersionedTeamCommand extends IdempotentCommand {
  teamId: string
  expectedVersion: number
}
export const consumerWaitingKeys = {
  current: [...CONSUMER_PROTECTED_QUERY_ROOTS.account, 'me', 'waiting-teams', 'current'] as const,
}

export function useCurrentConsumerWaiting() {
  const { apiClient } = useConsumerAuth()
  return useQuery({
    queryKey: consumerWaitingKeys.current,
    queryFn: async ({ signal }): Promise<ConsumerWaitingSnapshot | null> => {
      try {
        const response = await apiClient('/api/v1/consumers/me/waiting-teams/current', { method: 'get', signal })
        return response.data
      } catch (error) {
        if (error instanceof ApiError && error.status === 404 && error.code === 'WAITING_003') return null
        throw error
      }
    },
  })
}

export function issueWaitingPartyInvitation(
  apiClient: ApiClient,
  input: VersionedTeamCommand,
): Promise<WaitingInvitation> {
  return apiClient('/api/v1/consumers/me/waiting-teams/{teamId}/invitations', {
    method: 'post',
    pathParams: { teamId: input.teamId },
    body: { expectedVersion: input.expectedVersion },
    idempotencyKey: input.idempotencyKey,
  }).then((response) => response.data)
}

export function revokeWaitingPartyInvitation(
  apiClient: ApiClient,
  input: VersionedTeamCommand & { invitationId: string },
): Promise<WaitingInvitation> {
  return apiClient(
    '/api/v1/consumers/me/waiting-teams/{teamId}/invitations/{invitationId}/revocations',
    {
      method: 'post',
      pathParams: {
        teamId: input.teamId,
        invitationId: input.invitationId,
      },
      body: { expectedVersion: input.expectedVersion },
      idempotencyKey: input.idempotencyKey,
    },
  ).then((response) => response.data)
}

export function acceptWaitingPartyInvitation(
  apiClient: ApiClient,
  input: IdempotentCommand & { invitationCode: string },
): Promise<ConsumerWaitingSnapshot> {
  return apiClient('/api/v1/consumers/me/waiting-invitation-acceptances', {
    method: 'post',
    body: { invitationCode: input.invitationCode },
    idempotencyKey: input.idempotencyKey,
  }).then((response) => response.data)
}

export function departWaitingPartyMembership(
  apiClient: ApiClient,
  input: VersionedTeamCommand,
): Promise<ConsumerWaitingSnapshot> {
  return apiClient(
    '/api/v1/consumers/me/waiting-teams/{teamId}/membership-departures',
    {
      method: 'post',
      pathParams: { teamId: input.teamId },
      body: { expectedVersion: input.expectedVersion },
      idempotencyKey: input.idempotencyKey,
    },
  ).then((response) => response.data)
}

export function removeWaitingPartyMembership(
  apiClient: ApiClient,
  input: VersionedTeamCommand & { membershipId: string },
): Promise<ConsumerWaitingSnapshot> {
  return apiClient(
    '/api/v1/consumers/me/waiting-teams/{teamId}/memberships/{membershipId}/removals',
    {
      method: 'post',
      pathParams: {
        teamId: input.teamId,
        membershipId: input.membershipId,
      },
      body: { expectedVersion: input.expectedVersion },
      idempotencyKey: input.idempotencyKey,
    },
  ).then((response) => response.data)
}

export function proposeWaitingRepresentativeTransfer(
  apiClient: ApiClient,
  input: VersionedTeamCommand & { targetMembershipId: string },
): Promise<WaitingTransferOffer> {
  return apiClient(
    '/api/v1/consumers/me/waiting-teams/{teamId}/representative-transfer-offers',
    {
      method: 'post',
      pathParams: { teamId: input.teamId },
      body: {
        targetMembershipId: input.targetMembershipId,
        expectedVersion: input.expectedVersion,
      },
      idempotencyKey: input.idempotencyKey,
    },
  ).then((response) => response.data)
}

export function revokeWaitingRepresentativeTransfer(
  apiClient: ApiClient,
  input: VersionedTeamCommand & { offerId: string },
): Promise<WaitingTransferOffer> {
  return apiClient(
    '/api/v1/consumers/me/waiting-teams/{teamId}/representative-transfer-offers/{offerId}/revocations',
    {
      method: 'post',
      pathParams: { teamId: input.teamId, offerId: input.offerId },
      body: { expectedVersion: input.expectedVersion },
      idempotencyKey: input.idempotencyKey,
    },
  ).then((response) => response.data)
}
