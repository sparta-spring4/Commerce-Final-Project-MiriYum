import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { storeOperatorKeys, useStoreOperatorAuth } from '../../store-operator'
import type {
  CapacityBucketRequest,
  ReservationCapacitiesData,
  ReservationDetail,
  ReservationPageData,
  ReservationSort,
  ReservationStatus,
  ReservationTimePolicyDraftRequest,
  ReservationTimePolicyResponse,
} from '../model/types'

/**
 * 예약 운영 query·mutation.
 *
 * 키는 운영자 셸의 `storeOperatorKeys`를 그대로 쓴다. 매장이 바뀔 때 접두사
 * 하나로 이전 매장의 query가 함께 격리되어야 하므로 별도 트리를 만들지 않는다.
 */

export interface StoreReservationQuery {
  serviceDate?: string
  status?: ReservationStatus
  page: number
  size: number
  sort: ReservationSort
}

export function useStoreReservations(
  storeId: string,
  query: StoreReservationQuery,
) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.reservationPage(storeId, query),
    queryFn: async ({ signal }): Promise<ReservationPageData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservations',
        {
          method: 'get',
          pathParams: { storeId },
          query: {
            serviceDate: query.serviceDate,
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
    // 페이지를 넘길 때 목록이 빈 화면으로 깜빡이지 않게 이전 결과를 유지한다.
    placeholderData: (previous) => previous,
  })
}

export function useStoreReservation(storeId: string, reservationId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.reservation(storeId, reservationId),
    queryFn: async ({ signal }): Promise<ReservationDetail> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}',
        {
          method: 'get',
          pathParams: { storeId, reservationId },
          signal,
        },
      )
      return response.data
    },
  })
}

/**
 * 예약 상태를 바꾼 뒤 화면들을 맞춘다.
 *
 * 응답이 곧 확정된 예약이므로 상세 캐시는 그 값으로 바로 채운다. 목록은 조건별
 * 페이지가 여러 개라 어느 페이지에 있었는지 알 수 없으므로 전부 무효화한다.
 * 취소는 자리를 되돌리므로 공개 예약 가용성도 바뀐다.
 */
function refreshAfterReservationChange(
  queryClient: QueryClient,
  storeId: string,
  reservation: ReservationDetail,
): void {
  queryClient.setQueryData(
    storeOperatorKeys.reservation(storeId, reservation.reservationId),
    reservation,
  )
  void queryClient.invalidateQueries({
    queryKey: storeOperatorKeys.reservationPages(storeId),
  })
  void queryClient.invalidateQueries({ queryKey: ['store-search'] })
}

/**
 * 매장 사유 예약 취소.
 *
 * 사유가 필수다. 계약이 `minLength: 1`을 요구하고, 취소 이력에 남아 고객에게도
 * 설명되는 값이라 화면이 임의 문구로 채우지 않는다.
 */
export function useCancelStoreReservation(
  storeId: string,
  reservationId: string,
) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      reason: string
      idempotencyKey: string
    }): Promise<ReservationDetail> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}/cancellations',
        {
          method: 'post',
          pathParams: { storeId, reservationId },
          body: { reason: variables.reason },
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (reservation) =>
      refreshAfterReservationChange(queryClient, storeId, reservation),
  })
}

/**
 * 예약 방문 완료.
 *
 * 계약의 본문은 필드가 없는 명령이다. 빈 객체를 보낸다. 본문을 생략하면
 * Content-Type이 붙지 않아 서버가 415로 거절한다.
 */
export function useFulfillStoreReservation(
  storeId: string,
  reservationId: string,
) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      idempotencyKey: string
    }): Promise<ReservationDetail> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}/fulfillments',
        {
          method: 'post',
          pathParams: { storeId, reservationId },
          body: {},
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (reservation) =>
      refreshAfterReservationChange(queryClient, storeId, reservation),
  })
}

/**
 * 날짜별 수용량 전체 교체.
 *
 * 성공 응답의 점유·잔여를 그대로 표시한다. 클라이언트가 잔여를 다시 계산해
 * 원장처럼 보관하지 않는다. 예약이 들어오면 그 값은 곧 낡는다.
 */
export function useReplaceReservationCapacities(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      serviceDate: string
      buckets: CapacityBucketRequest[]
      idempotencyKey: string
    }): Promise<ReservationCapacitiesData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservation-capacities/{serviceDate}',
        {
          method: 'put',
          pathParams: { storeId, serviceDate: variables.serviceDate },
          body: { buckets: variables.buckets },
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => {
      // 공개 예약 가용성이 바뀐다.
      void queryClient.invalidateQueries({ queryKey: ['store-search'] })
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.publishedStore(storeId),
      })
    },
  })
}

export function useSaveTimePolicyDraft(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useMutation({
    mutationFn: async (variables: {
      body: ReservationTimePolicyDraftRequest
      idempotencyKey: string
    }): Promise<ReservationTimePolicyResponse> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservation-time-policies',
        {
          method: 'put',
          pathParams: { storeId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
  })
}

export function usePublishTimePolicy(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      version: number
      body: {
        publicationMode: 'IMMEDIATE' | 'SCHEDULED'
        effectiveAt?: string
        changeReason: string
      }
      idempotencyKey: string
    }): Promise<ReservationTimePolicyResponse> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservation-time-policies/{version}/publication',
        {
          method: 'post',
          pathParams: { storeId, version: variables.version },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['store-search'] })
    },
  })
}

export function useCancelTimePolicyPublication(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useMutation({
    mutationFn: async (variables: {
      version: number
      body: { changeReason: string }
      idempotencyKey: string
    }): Promise<ReservationTimePolicyResponse> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservation-time-policies/{version}/publication-cancellation',
        {
          method: 'post',
          pathParams: { storeId, version: variables.version },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
  })
}
