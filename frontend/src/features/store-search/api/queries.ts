import { useQuery } from '@tanstack/react-query'
import { ApiContractError } from '../../../shared/api/apiError'
import { publicApiClient } from '../../../shared/api/publicApiClient'
import type { components } from '../../../shared/api/generated/store-search'
import type {
  CatalogItem,
  StoreDetail,
  StoreSearchQuery,
} from '../model/searchParams'

/** `searchStores` 200 응답의 data. 2차 MVP에서 union이 됐다. */
type StoreSearchResponseData =
  components['schemas']['StoreSearchSuccessResponse']['data']

/**
 * 2차 MVP가 `searchStores` 응답을 union으로 넓혔다.
 *
 * - `searchInput`을 보내면 cursor 모드(`IntegratedStoreSearchData`)
 * - 보내지 않으면 page 모드(`StorePageData`)
 *
 * 1차 MVP 검색은 `searchInput`을 만들지 않으므로 언제나 page 모드다. 그 사실을
 * 여기서 한 번 좁혀 두면 화면은 `page`가 있는 타입만 다루면 된다.
 * 통합 검색 화면은 2차 MVP #112가 자기 query로 따로 소유한다.
 */
function toPageData(
  data: StoreSearchResponseData,
): Extract<StoreSearchResponseData, { page: unknown }> {
  if (!('page' in data)) {
    // searchInput을 보내지 않았는데 cursor 모드가 왔다. 조용히 빈 목록으로
    // 넘기면 사용자에게 결과 없음을 잘못 보여 주므로 계약 위반으로 올린다.
    throw new ApiContractError(200, 'expectedPagedStoreSearch')
  }
  return data
}

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
      return toPageData(response.data)
    },
    enabled,
    /*
     * 페이지를 넘길 때만 이전 결과를 유지한다.
     *
     * 조건이 바뀔 때도 유지하면 칩과 총 개수는 새 조건인데 목록은 이전 조건의
     * 결과인 구간이 생긴다. 그 사이 카드를 누르면 이전 조건의 매장에 새 예약
     * 조건을 붙여 예약 화면으로 넘어간다.
     */
    placeholderData: (previous, previousQuery) =>
      keepsSameConditions(previousQuery?.queryKey, query) ? previous : undefined,
  })
}

/**
 * 두 검색 query가 페이지 번호만 다른지 판정한다.
 *
 * query key의 마지막 칸이 `toSearchQuery`가 만든 조건 객체다. 필드를 손으로
 * 나열하지 않고 통째로 비교해, 계약에 조건이 추가돼도 판정이 함께 따라간다.
 */
function keepsSameConditions(
  previousKey: unknown,
  next: StoreSearchQuery,
): boolean {
  if (!Array.isArray(previousKey)) {
    return false
  }
  const previous: unknown = previousKey[previousKey.length - 1]
  if (typeof previous !== 'object' || previous === null) {
    return false
  }
  return (
    conditionSignature(previous as StoreSearchQuery) === conditionSignature(next)
  )
}

function conditionSignature(query: StoreSearchQuery): string {
  return JSON.stringify(
    Object.entries(query)
      .filter(([field]) => field !== 'page')
      .sort(([a], [b]) => a.localeCompare(b)),
  )
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
