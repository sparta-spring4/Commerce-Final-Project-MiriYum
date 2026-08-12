import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { publicApiClient } from '../../../shared/api/publicApiClient'
import { useStoreOperatorAuth } from '../StoreOperatorAuthProvider'
import type {
  CatalogItem,
  ManagedStore,
  PublicStoreDetail,
  StoreCreateRequest,
  StoreUpdateRequest,
} from '../model/types'

/**
 * 매장 운영자 query 키.
 *
 * 모든 키가 매장 ID를 앞에 둔다. 현재 매장이 바뀔 때 접두사 하나로 이전 매장의
 * query를 취소·제거할 수 있어야 하기 때문이다.
 */
export const storeOperatorKeys = {
  all: ['store-operator'] as const,
  store: (storeId: string) => [...storeOperatorKeys.all, storeId] as const,
  managedStore: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'managed-store'] as const,
  publishedStore: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'published-store'] as const,
  menus: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'menus'] as const,
  menu: (storeId: string, menuId: string) =>
    [...storeOperatorKeys.menus(storeId), menuId] as const,
  reservations: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'reservations'] as const,
  /**
   * 목록 페이지 전체를 가리키는 키.
   *
   * 상세를 함께 무효화하지 않으려고 한 단계를 더 둔다. `reservations`로 무효화하면
   * 방금 응답으로 채운 상세까지 다시 불러 화면이 잠깐 이전 상태로 돌아간다.
   */
  reservationPages: (storeId: string) =>
    [...storeOperatorKeys.reservations(storeId), 'page'] as const,
  /** 조회 조건은 키에 그대로 담는다. 조건이 다르면 다른 캐시 항목이다. */
  reservationPage: (storeId: string, query: object) =>
    [...storeOperatorKeys.reservationPages(storeId), query] as const,
  reservation: (storeId: string, reservationId: string) =>
    [...storeOperatorKeys.reservations(storeId), reservationId] as const,
}

type CatalogName = 'store-categories' | 'store-tags' | 'menu-categories'

const CATALOG_PATH = {
  'store-categories': '/api/v1/store-categories',
  'store-tags': '/api/v1/store-tags',
  'menu-categories': '/api/v1/menu-categories',
} as const

/**
 * catalog 표시명은 서버가 소유한다. code로 표시명을 추측하거나 태그를 임의로
 * 만들어 쓰지 않는다. 공개 조회이므로 운영자 토큰을 붙이지 않는다.
 */
export function useOperatorCatalog(name: CatalogName) {
  return useQuery({
    queryKey: [...storeOperatorKeys.all, 'catalog', name],
    queryFn: async ({ signal }): Promise<CatalogItem[]> => {
      const response = await publicApiClient(CATALOG_PATH[name], {
        method: 'get',
        signal,
      })
      return response.data.items
    },
    staleTime: 5 * 60 * 1000,
  })
}

/** 운영 중인 매장 단건. 목록 계약이 없으므로 알려진 ID로만 조회한다. */
export function useManagedStore(storeId: string, enabled = true) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    enabled: enabled && storeId.length > 0,
    queryKey: storeOperatorKeys.managedStore(storeId),
    queryFn: async ({ signal }): Promise<ManagedStore> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}',
        { method: 'get', pathParams: { storeId }, signal },
      )
      return response.data
    },
  })
}

/**
 * 게시된 공개 매장 상세.
 *
 * 운영자용 영업시간 조회 계약이 없다. 예약 접수 시간대를 편집할 때 "게시된
 * 영업시간" 을 참조로 보여 주기 위해서만 읽는다. 이 값으로 편집 폼을 미리
 * 채우지 않는다. 그건 조회 계약이 승인된 뒤의 일이다.
 */
export function usePublishedStoreDetail(storeId: string, enabled = true) {
  return useQuery({
    queryKey: storeOperatorKeys.publishedStore(storeId),
    queryFn: async ({ signal }): Promise<PublicStoreDetail> => {
      const response = await publicApiClient('/api/v1/stores/{storeId}', {
        method: 'get',
        pathParams: { storeId },
        signal,
      })
      return response.data
    },
    enabled,
    // 참조용이라 실패해도 편집을 막지 않는다. 화면이 없음 상태를 표시한다.
    retry: false,
  })
}

export function useCreateStore() {
  const { apiClient } = useStoreOperatorAuth()

  return useMutation({
    mutationFn: async (variables: {
      body: StoreCreateRequest
      idempotencyKey: string
    }): Promise<ManagedStore> => {
      const response = await apiClient('/api/v1/store-operators/stores', {
        method: 'post',
        body: variables.body,
        idempotencyKey: variables.idempotencyKey,
      })
      return response.data
    },
  })
}

export function useUpdateStore(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: StoreUpdateRequest
      idempotencyKey: string
    }): Promise<ManagedStore> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}',
        {
          method: 'patch',
          pathParams: { storeId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (store) => {
      queryClient.setQueryData(storeOperatorKeys.managedStore(storeId), store)
      // 공개 상세·검색 결과의 표시 정보가 바뀐다.
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.publishedStore(storeId),
      })
      void queryClient.invalidateQueries({ queryKey: ['store-search'] })
    },
  })
}
