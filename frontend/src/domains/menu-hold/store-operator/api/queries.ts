import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { storeOperatorKeys } from '../../../../app/shells/store-operator/queryKeys'
import { useStoreOperatorAuth } from '../../../../app/shells/store-operator/StoreOperatorAuthProvider'
import type { MenuInventoryBucket, MenuInventoryCreateRequest, MenuInventoryPageData, MenuInventoryUpdateRequest } from '../model/types'

export interface InventoryPageQuery { serviceDate?: string; menuId?: string; page: number; size: number }

export function useMenuInventoryBuckets(storeId: string, query: InventoryPageQuery) {
  const { apiClient } = useStoreOperatorAuth()
  return useQuery({
    queryKey: storeOperatorKeys.inventoryPage(storeId, query),
    queryFn: async ({ signal }): Promise<MenuInventoryPageData> => {
      const response = await apiClient('/api/v1/store-operators/stores/{storeId}/menu-inventory-buckets', {
        method: 'get', pathParams: { storeId }, query: { ...query }, signal,
      })
      return response.data
    },
    placeholderData: (previous) => previous,
  })
}

export function useCreateMenuInventoryBucket(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (variables: { body: MenuInventoryCreateRequest; idempotencyKey: string }): Promise<MenuInventoryBucket> => {
      const response = await apiClient('/api/v1/store-operators/stores/{storeId}/menu-inventory-buckets', {
        method: 'post', pathParams: { storeId }, body: variables.body, idempotencyKey: variables.idempotencyKey,
      })
      return response.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: storeOperatorKeys.inventoryBuckets(storeId) })
      void queryClient.invalidateQueries({ queryKey: ['store-search'] })
    },
  })
}

export function useUpdateMenuInventoryBucket(storeId: string, inventoryBucketId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (variables: { body: MenuInventoryUpdateRequest; idempotencyKey: string }): Promise<MenuInventoryBucket> => {
      const response = await apiClient('/api/v1/store-operators/stores/{storeId}/menu-inventory-buckets/{inventoryBucketId}', {
        method: 'patch', pathParams: { storeId, inventoryBucketId }, body: variables.body, idempotencyKey: variables.idempotencyKey,
      })
      return response.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: storeOperatorKeys.inventoryBuckets(storeId) })
      void queryClient.invalidateQueries({ queryKey: ['store-search'] })
    },
  })
}
