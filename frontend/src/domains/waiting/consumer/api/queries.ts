import { useMutation, useQuery } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../../../../shared/api/apiError'
import type { ApiClient } from '../../../../shared/api/client'
import type { components } from '../../../../shared/api/generated/waiting'
import { CONSUMER_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/consumer/querySession'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import type { WaitingLocationMeasurement } from '../model/locationMeasurement'

export type ConsumerWaitingSnapshot = components['schemas']['WaitingConsumerSnapshot']
export type ConsumerWaitingAvailability =
  components['schemas']['WaitingReceptionAvailability']
export type WaitingLocationProofRequest =
  components['schemas']['WaitingLocationProofRequest']
export type WaitingLocationProofSnapshot =
  components['schemas']['WaitingLocationProofSnapshot']
export type ConsumerWaitingCreateRequest =
  components['schemas']['WaitingConsumerCreateRequest']

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
