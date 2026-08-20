import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { publicApiClient } from '../../../../shared/api/publicApiClient'
import { useStoreOperatorAuth } from '../../../../app/shells/store-operator/StoreOperatorAuthProvider'
import { storeOperatorKeys } from '../../../../app/shells/store-operator/queryKeys'
import type {
  CatalogItem,
  ManagedStore,
  PublicImage,
  PublicStoreDetail,
  StoreCreateRequest,
  StoreUpdateRequest,
} from '../model/types'

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

/** 매장에 연결된 공개 이미지는 매장 정보 PATCH와 별도 API로 관리한다. */
export function useStoreImages(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    enabled: storeId.length > 0,
    queryKey: storeOperatorKeys.images(storeId),
    queryFn: async ({ signal }): Promise<PublicImage[]> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/images',
        { method: 'get', pathParams: { storeId }, signal },
      )
      return response.data
    },
  })
}

export function useUploadStoreImage(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      file: File
      idempotencyKey: string
    }): Promise<PublicImage> => {
      const multipart = new FormData()
      multipart.append('file', variables.file)
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/images',
        {
          method: 'post',
          pathParams: { storeId },
          multipart,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.images(storeId),
      })
    },
  })
}

export function useReplaceStoreImage(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      imageId: string
      file: File
      idempotencyKey: string
    }): Promise<PublicImage> => {
      const multipart = new FormData()
      multipart.append('file', variables.file)
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/images/{imageId}',
        {
          method: 'put',
          pathParams: { storeId, imageId: variables.imageId },
          multipart,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.images(storeId),
      })
    },
  })
}

export function useDeleteStoreImage(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      imageId: string
      idempotencyKey: string
    }): Promise<void> => {
      await apiClient(
        '/api/v1/store-operators/stores/{storeId}/images/{imageId}',
        {
          method: 'delete',
          pathParams: { storeId, imageId: variables.imageId },
          allowNoContent: true,
          idempotencyKey: variables.idempotencyKey,
        },
      )
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.images(storeId),
      })
    },
  })
}
