import { useQuery } from '@tanstack/react-query'
import { storeOperatorKeys } from '../../../../app/shells/store-operator/queryKeys'
import { useStoreOperatorAuth } from '../../../../app/shells/store-operator/StoreOperatorAuthProvider'
import type { DashboardSnapshot } from '../model/types'

export function useStoreDashboardStatistics(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  return useQuery({
    queryKey: storeOperatorKeys.dashboardStatistics(storeId),
    queryFn: async ({ signal }): Promise<DashboardSnapshot> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/dashboard-statistics',
        { method: 'get', pathParams: { storeId }, signal },
      )
      return response.data
    },
  })
}
