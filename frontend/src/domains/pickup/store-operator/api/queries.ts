import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { storeOperatorKeys } from '../../../../app/shells/store-operator/queryKeys'
import { useStoreOperatorAuth } from '../../../../app/shells/store-operator/StoreOperatorAuthProvider'
import type { PickupPageData, PickupReservation, PickupStatus } from '../model/types'

export interface PickupPageQuery {
  pickupDate?: string
  status?: PickupStatus
  page: number
  size: number
  sort: 'pickupDate,asc' | 'pickupDate,desc' | 'createdAt,asc' | 'createdAt,desc'
}

export function useStorePickupReservations(storeId: string, query: PickupPageQuery) {
  const { apiClient } = useStoreOperatorAuth()
  return useQuery({
    queryKey: storeOperatorKeys.pickupPage(storeId, query),
    queryFn: async ({ signal }): Promise<PickupPageData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/pickup-reservations',
        {
          method: 'get',
          pathParams: { storeId },
          query: {
            pickupDate: query.pickupDate,
            status: query.status,
            page: query.page,
            size: query.size,
            sort: query.sort,
          },
          signal,
        },
      )
      return response.data
    },
    placeholderData: (previous) => previous,
  })
}

export function useStorePickupReservation(storeId: string, pickupReservationId: string) {
  const { apiClient } = useStoreOperatorAuth()
  return useQuery({
    queryKey: storeOperatorKeys.pickup(storeId, pickupReservationId),
    queryFn: async ({ signal }): Promise<PickupReservation> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}',
        { method: 'get', pathParams: { storeId, pickupReservationId }, signal },
      )
      return response.data
    },
  })
}

function refreshPickup(
  queryClient: QueryClient,
  storeId: string,
  pickup: PickupReservation,
) {
  queryClient.setQueryData(
    storeOperatorKeys.pickup(storeId, pickup.pickupReservationId),
    pickup,
  )
  void queryClient.invalidateQueries({ queryKey: storeOperatorKeys.pickupPages(storeId) })
}

export function useFulfillStorePickup(storeId: string, pickupReservationId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (idempotencyKey: string): Promise<PickupReservation> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}/fulfillments',
        {
          method: 'post',
          pathParams: { storeId, pickupReservationId },
          body: {},
          idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (pickup) => refreshPickup(queryClient, storeId, pickup),
  })
}

export function useCancelStorePickup(storeId: string, pickupReservationId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (variables: { reason: string; idempotencyKey: string }): Promise<PickupReservation> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}/cancellations',
        {
          method: 'post',
          pathParams: { storeId, pickupReservationId },
          body: { reason: variables.reason },
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (pickup) => refreshPickup(queryClient, storeId, pickup),
  })
}
