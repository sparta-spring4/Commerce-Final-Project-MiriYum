import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../../../shared/api/apiError'
import type { components } from '../../../../shared/api/generated/waiting'
import { CONSUMER_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/consumer/querySession'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'

export type ConsumerWaitingSnapshot = components['schemas']['WaitingConsumerSnapshot']
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
