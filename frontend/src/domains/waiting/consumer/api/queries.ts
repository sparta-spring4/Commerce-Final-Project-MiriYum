import { useMutation, useQuery } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../../../../shared/api/apiError'
import type { ApiClient } from '../../../../shared/api/client'
import type { components } from '../../../../shared/api/generated/waiting'
import { CONSUMER_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/consumer/querySession'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import type { WaitingLocationMeasurement } from '../model/locationMeasurement'

export type ConsumerWaitingSnapshot = components['schemas']['WaitingConsumerSnapshot']
export type WaitingInvitation = components['schemas']['WaitingInvitation']
export type WaitingTransferOffer = components['schemas']['WaitingTransferOffer']
export type ConsumerWaitingAvailability =
  components['schemas']['WaitingReceptionAvailability']
export type WaitingLocationProofRequest =
  components['schemas']['WaitingLocationProofRequest']
export type WaitingLocationProofSnapshot =
  components['schemas']['WaitingLocationProofSnapshot']
export type ConsumerWaitingCreateRequest =
  components['schemas']['WaitingConsumerCreateRequest']

interface IdempotentCommand {
  idempotencyKey: string
}

interface VersionedTeamCommand extends IdempotentCommand {
  teamId: string
  expectedVersion: number
}
export const consumerWaitingKeys = {
  current: [...CONSUMER_PROTECTED_QUERY_ROOTS.account, 'me', 'waiting-teams', 'current'] as const,
  availability: (storeId: string) =>
    [
      ...CONSUMER_PROTECTED_QUERY_ROOTS.account,
      'me',
      'stores',
      storeId,
      'waiting-availability',
    ] as const,
}

const CURRENT_PATH = '/api/v1/consumers/me/waiting-teams/current' as const
const AVAILABILITY_PATH =
  '/api/v1/consumers/me/stores/{storeId}/waiting-availabilities' as const
const PROOF_PATH =
  '/api/v1/consumers/me/stores/{storeId}/waiting-location-proofs' as const
const CREATE_PATH =
  '/api/v1/consumers/me/stores/{storeId}/waiting-teams' as const

async function fetchCurrentConsumerWaiting(
  apiClient: ApiClient,
  signal?: AbortSignal,
): Promise<ConsumerWaitingSnapshot | null> {
  try {
    const response = await apiClient(CURRENT_PATH, { method: 'get', signal })
    return response.data
  } catch (error) {
    if (error instanceof ApiError && error.status === 404 && error.code === 'WAITING_003') return null
    throw error
  }
}

export function useCurrentConsumerWaiting() {
  const { apiClient } = useConsumerAuth()
  return useQuery({
    queryKey: consumerWaitingKeys.current,
    queryFn: ({ signal }) => fetchCurrentConsumerWaiting(apiClient, signal),
  })
}

export function useConsumerWaitingAvailability(storeId: string) {
  const { apiClient } = useConsumerAuth()
  return useQuery({
    queryKey: consumerWaitingKeys.availability(storeId),
    enabled: storeId.length > 0,
    staleTime: 0,
    queryFn: async ({ signal }): Promise<ConsumerWaitingAvailability> => {
      const response = await apiClient(AVAILABILITY_PATH, {
        method: 'get',
        pathParams: { storeId },
        signal,
      })
      return response.data
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

export async function issueConsumerWaitingLocationProof(
  apiClient: ApiClient,
  storeId: string,
  body: WaitingLocationMeasurement,
  signal?: AbortSignal,
): Promise<WaitingLocationProofSnapshot> {
  const response = await apiClient(PROOF_PATH, {
    method: 'post',
    pathParams: { storeId },
    signal,
    // The generated optional-discriminator OneOf rejects its valid MEASURED
    // branch in TypeScript. Keep that generator limitation at this API edge.
    body: body as unknown as WaitingLocationProofRequest,
  })
  return response.data
}

export function useCreateConsumerWaitingTeam(storeId: string) {
  const { apiClient } = useConsumerAuth()

  return useMutation({
    mutationFn: async (input: {
      body: ConsumerWaitingCreateRequest
      idempotencyKey: string
      signal?: AbortSignal
    }): Promise<ConsumerWaitingSnapshot> => {
      const response = await apiClient(CREATE_PATH, {
        method: 'post',
        pathParams: { storeId },
        body: input.body,
        idempotencyKey: input.idempotencyKey,
        signal: input.signal,
      })
      return response.data
    },
  })
}

export async function refetchCurrentConsumerWaiting(
  queryClient: QueryClient,
  apiClient: ApiClient,
  lifecycleSignal?: AbortSignal,
): Promise<ConsumerWaitingSnapshot | null> {
  await queryClient.invalidateQueries({ queryKey: consumerWaitingKeys.current })
  return queryClient.fetchQuery({
    queryKey: consumerWaitingKeys.current,
    queryFn: ({ signal }) =>
      fetchCurrentConsumerWaiting(
        apiClient,
        lifecycleSignal === undefined
          ? signal
          : AbortSignal.any([signal, lifecycleSignal]),
      ),
  })
}
