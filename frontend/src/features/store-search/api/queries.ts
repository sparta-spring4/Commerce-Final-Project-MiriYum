import { useQuery } from '@tanstack/react-query'
import { publicApiClient } from '../../../shared/api/publicApiClient'
import type {
  CatalogItem,
  StoreDetail,
  StoreSearchQuery,
} from '../model/searchParams'

/**
 * 공개 매장 조회 query.
 *
 * 경로와 응답 타입은 생성 타입에서 온다. 응답 필드를 여기서 다시 정의하지 않는다.
 */

export const storeSearchKeys = {
  all: ['store-search'] as const,
  catalog: (name: CatalogName) => [...storeSearchKeys.all, 'catalog', name] as const,
  list: (query: StoreSearchQuery) =>
    [...storeSearchKeys.all, 'stores', query] as const,
  detail: (storeId: string, query: StoreSearchQuery) =>
    [...storeSearchKeys.all, 'store', storeId, query] as const,
  menus: (storeId: string) =>
    [...storeSearchKeys.all, 'store', storeId, 'menus'] as const,
}

type CatalogName = 'store-categories' | 'store-tags' | 'menu-categories'

const CATALOG_PATH = {
  'store-categories': '/api/v1/store-categories',
  'store-tags': '/api/v1/store-tags',
  'menu-categories': '/api/v1/menu-categories',
} as const

/**
 * catalog 표시명은 서버가 소유한다.
 *
 * 화면이 code로 표시명을 추측하면 운영자가 catalog를 바꿔도 화면이 따라가지 않고,
 * 없는 카테고리를 지어내게 된다. 항상 이 조회 결과만 표시에 사용한다.
 */
export function useCatalog(name: CatalogName) {
  return useQuery({
    queryKey: storeSearchKeys.catalog(name),
    queryFn: async ({ signal }) => {
      const response = await publicApiClient(CATALOG_PATH[name], {
        method: 'get',
        signal,
      })
      return response.data.items
    },
    // catalog는 거의 바뀌지 않는다. 화면 이동마다 다시 부르지 않는다.
    staleTime: 5 * 60 * 1000,
  })
}

/** code → displayName 조회표. 표시 지점마다 배열을 훑지 않게 한다. */
export function toDisplayNameMap(
  items: CatalogItem[] | undefined,
): ReadonlyMap<string, string> {
  return new Map((items ?? []).map((item) => [item.code, item.displayName]))
}

export function useStoreSearch(query: StoreSearchQuery, enabled = true) {
  return useQuery({
    queryKey: storeSearchKeys.list(query),
    queryFn: async ({ signal }) => {
      const response = await publicApiClient('/api/v1/stores', {
        method: 'get',
        query,
        signal,
      })
      return response.data
    },
    enabled,
    // 페이지를 넘길 때 목록이 빈 화면으로 깜빡이지 않게 이전 결과를 유지한다.
    placeholderData: (previous) => previous,
  })
}

export function useStoreDetail(storeId: string, query: StoreSearchQuery) {
  return useQuery({
    queryKey: storeSearchKeys.detail(storeId, query),
    queryFn: async ({ signal }): Promise<StoreDetail> => {
      const response = await publicApiClient('/api/v1/stores/{storeId}', {
        method: 'get',
        pathParams: { storeId },
        query,
        signal,
      })
      return response.data
    },
  })
}

export function useStoreMenus(storeId: string) {
  return useQuery({
    queryKey: storeSearchKeys.menus(storeId),
    queryFn: async ({ signal }) => {
      const response = await publicApiClient('/api/v1/stores/{storeId}/menus', {
        method: 'get',
        pathParams: { storeId },
        signal,
      })
      return response.data.items
    },
  })
}
