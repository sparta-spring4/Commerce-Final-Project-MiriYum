import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CONSUMER_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/consumer/querySession'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import { keepsSameListConditions } from '../../../../shared/api/listQuery'
import type { ReservationHistoryStatus, ReservationSort } from '../model/reservationDisplay'

export interface ReservationHistoryQuery {
  status?: ReservationHistoryStatus
  page: number
  sort: ReservationSort
}

export const consumerReservationHistoryKeys = {
  all: [...CONSUMER_PROTECTED_QUERY_ROOTS.account, 'me', 'reservations'] as const,
  list: (query: ReservationHistoryQuery) => [...consumerReservationHistoryKeys.all, query] as const,
}

export function useMyReservations(query: ReservationHistoryQuery) {
  const { apiClient } = useConsumerAuth()
  return useQuery({
    queryKey: consumerReservationHistoryKeys.list(query),
    queryFn: async ({ signal }) => {
      const response = await apiClient('/api/v1/consumers/me/reservations', {
        method: 'get',
        query: { status: query.status, page: query.page > 0 ? query.page : undefined, sort: query.sort },
        signal,
      })
      return response.data
    },
    placeholderData: (previous, previousQuery) =>
      keepsSameListConditions(previousQuery?.queryKey, query) ? previous : undefined,
  })
}

export function invalidateMyReservations(queryClient: ReturnType<typeof useQueryClient>): Promise<void> {
  return queryClient.invalidateQueries({ queryKey: consumerReservationHistoryKeys.all })
}
