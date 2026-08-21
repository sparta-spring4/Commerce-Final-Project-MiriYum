import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { useStoreOperatorAuth } from '../../../../app/shells/store-operator/StoreOperatorAuthProvider'
import type {
  ManagedMenu,
  MenuSellingStatus,
  MenuVisibility,
  MenuWriteRequest,
  PublicationMode,
  RepresentativeMenuReplaceRequest,
  RepresentativeMenuSetting,
} from '../model/types'
import { storeOperatorKeys } from '../../../../app/shells/store-operator/queryKeys'

/**
 * 운영자 메뉴 조회·쓰기.
 *
 * 버전(DRAFT/SCHEDULED/PUBLISHED/RETIRED)·노출(VISIBLE/HIDDEN)·판매
 * (SELLING/SOLD_OUT/PAUSED)는 독립 축이다. 한 요청으로 합치지 않는다.
 * 제공 구간 재고의 AVAILABLE/SOLD_OUT은 다른 도메인이며 여기서 저장하지 않는다.
 */

function refreshMenuViews(
  queryClient: QueryClient,
  storeId: string,
  menu: ManagedMenu,
): void {
  queryClient.setQueryData(storeOperatorKeys.menu(storeId, menu.menuId), menu)
  void queryClient.invalidateQueries({
    queryKey: storeOperatorKeys.menus(storeId),
  })
  // 공개 메뉴 목록·매장 상세의 대표 메뉴 표시가 바뀐다.
  void queryClient.invalidateQueries({ queryKey: ['store-search'] })
}

export function useManagedMenus(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.menus(storeId),
    queryFn: async ({ signal }): Promise<ManagedMenu[]> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus',
        { method: 'get', pathParams: { storeId }, signal },
      )
      // 이 목록 계약은 page 봉투가 아니라 배열을 그대로 준다.
      return response.data
    },
  })
}

export function useManagedMenu(storeId: string, menuId: string, enabled = true) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.menu(storeId, menuId),
    queryFn: async ({ signal }): Promise<ManagedMenu> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus/{menuId}',
        { method: 'get', pathParams: { storeId, menuId }, signal },
      )
      return response.data
    },
    enabled,
  })
}

export function useRepresentativeMenus(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.representativeMenus(storeId),
    queryFn: async ({ signal }): Promise<RepresentativeMenuSetting> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/representative-menus',
        { method: 'get', pathParams: { storeId }, signal },
      )
      return response.data
    },
  })
}

export function useReplaceRepresentativeMenus(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: RepresentativeMenuReplaceRequest
      idempotencyKey: string
    }): Promise<RepresentativeMenuSetting> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/representative-menus',
        {
          method: 'put',
          pathParams: { storeId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (setting) => {
      queryClient.setQueryData(
        storeOperatorKeys.representativeMenus(storeId),
        setting,
      )
      void queryClient.invalidateQueries({ queryKey: ['store-search'] })
    },
  })
}

export function useCreateMenu(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: MenuWriteRequest
      idempotencyKey: string
    }): Promise<ManagedMenu> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus',
        {
          method: 'post',
          pathParams: { storeId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (menu) => refreshMenuViews(queryClient, storeId, menu),
  })
}

export function useUpdateMenuDraft(storeId: string, menuId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: MenuWriteRequest
      idempotencyKey: string
    }): Promise<ManagedMenu> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus/{menuId}',
        {
          method: 'put',
          pathParams: { storeId, menuId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (menu) => refreshMenuViews(queryClient, storeId, menu),
  })
}

export interface MenuPublicationInput {
  mode: PublicationMode
  /** SCHEDULED일 때만 보낸다. IMMEDIATE에 함께 보내면 계약 위반이다. */
  effectiveAt?: string
  changeReason: string
}

export function usePublishMenu(storeId: string, menuId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: MenuPublicationInput
      idempotencyKey: string
    }): Promise<ManagedMenu> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus/{menuId}/publications',
        {
          method: 'post',
          pathParams: { storeId, menuId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (menu) => refreshMenuViews(queryClient, storeId, menu),
  })
}

export function useCancelMenuPublication(storeId: string, menuId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: { changeReason: string }
      idempotencyKey: string
    }): Promise<ManagedMenu> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus/{menuId}/publication-cancellations',
        {
          method: 'post',
          pathParams: { storeId, menuId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (menu) => refreshMenuViews(queryClient, storeId, menu),
  })
}

export function useRetireMenu(storeId: string, menuId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: { changeReason: string }
      idempotencyKey: string
    }): Promise<ManagedMenu> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus/{menuId}/retirements',
        {
          method: 'post',
          pathParams: { storeId, menuId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (menu) => refreshMenuViews(queryClient, storeId, menu),
  })
}

export function useChangeMenuVisibility(storeId: string, menuId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: { visibility: MenuVisibility; changeReason: string }
      idempotencyKey: string
    }): Promise<ManagedMenu> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus/{menuId}/visibility',
        {
          method: 'patch',
          pathParams: { storeId, menuId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (menu) => refreshMenuViews(queryClient, storeId, menu),
  })
}

export function useChangeMenuSellingStatus(storeId: string, menuId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: { sellingStatus: MenuSellingStatus; changeReason: string }
      idempotencyKey: string
    }): Promise<ManagedMenu> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus/{menuId}/selling-status',
        {
          method: 'patch',
          pathParams: { storeId, menuId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (menu) => refreshMenuViews(queryClient, storeId, menu),
  })
}

export function usePutMenuImage(storeId: string, menuId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      file: File
      idempotencyKey: string
    }): Promise<string> => {
      const multipart = new FormData()
      multipart.append('file', variables.file)
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus/{menuId}/images',
        {
          method: 'put',
          pathParams: { storeId, menuId },
          multipart,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data.url
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.menu(storeId, menuId),
      })
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.menus(storeId),
      })
      void queryClient.invalidateQueries({ queryKey: ['store-search'] })
    },
  })
}

export function useDeleteMenuImage(storeId: string, menuId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (idempotencyKey: string): Promise<void> => {
      await apiClient(
        '/api/v1/store-operators/stores/{storeId}/menus/{menuId}/images',
        {
          method: 'delete',
          pathParams: { storeId, menuId },
          allowNoContent: true,
          idempotencyKey,
        },
      )
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.menu(storeId, menuId),
      })
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.menus(storeId),
      })
      void queryClient.invalidateQueries({ queryKey: ['store-search'] })
    },
  })
}
