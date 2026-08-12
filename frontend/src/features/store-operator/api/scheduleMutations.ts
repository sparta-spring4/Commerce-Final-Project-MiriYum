import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { useStoreOperatorAuth } from '../StoreOperatorAuthProvider'
import type {
  DailySchedule,
  DailyTimeSlots,
  DayOfWeek,
  OperatingHoursData,
  RegularClosureData,
  ReservationTimeSlotsData,
  SchedulePublicationRequest,
  TemporaryClosureData,
  TemporaryClosureReason,
} from '../model/types'
import { storeOperatorKeys } from './queries'

/**
 * 운영 스케줄 쓰기.
 *
 * 네 계약(영업시간·예약 접수 시간대·정기 휴무·임시 휴무) 모두 운영자용 GET이
 * 없다. 그래서 응답으로 받은 버전·식별자를 화면이 보존하고, 여기서는 조회 query를
 * 만들지 않는다. 없는 조회 API를 발명하지 않는다.
 *
 * 게시가 성공하면 공개 매장 상세와 검색 결과의 표시가 바뀌므로 그 query만
 * 무효화한다. 기존 예약은 서버가 자동으로 옮기거나 취소하지 않으며 클라이언트도
 * 손대지 않는다.
 */
function invalidatePublishedViews(
  queryClient: QueryClient,
  storeId: string,
): void {
  void queryClient.invalidateQueries({
    queryKey: storeOperatorKeys.publishedStore(storeId),
  })
  void queryClient.invalidateQueries({ queryKey: ['store-search'] })
}

interface DraftVariables<T> {
  body: T
  idempotencyKey: string
}

interface VersionVariables<T> {
  version: number
  body: T
  idempotencyKey: string
}

export function useSaveOperatingHoursDraft(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useMutation({
    mutationFn: async (
      variables: DraftVariables<{ days: DailySchedule[] }>,
    ): Promise<OperatingHoursData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/operating-hours',
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

export function usePublishOperatingHours(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (
      variables: VersionVariables<SchedulePublicationRequest>,
    ): Promise<OperatingHoursData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/operating-hours/{version}/publication',
        {
          method: 'post',
          pathParams: { storeId, version: variables.version },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => invalidatePublishedViews(queryClient, storeId),
  })
}

export function useCancelOperatingHoursPublication(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (
      variables: VersionVariables<{ changeReason: string }>,
    ): Promise<OperatingHoursData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/operating-hours/{version}/publication-cancellation',
        {
          method: 'post',
          pathParams: { storeId, version: variables.version },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => invalidatePublishedViews(queryClient, storeId),
  })
}

export function useSaveReservationTimeSlotsDraft(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useMutation({
    mutationFn: async (
      variables: DraftVariables<{ days: DailyTimeSlots[] }>,
    ): Promise<ReservationTimeSlotsData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservation-time-slots',
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

export function usePublishReservationTimeSlots(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (
      variables: VersionVariables<SchedulePublicationRequest>,
    ): Promise<ReservationTimeSlotsData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservation-time-slots/{version}/publication',
        {
          method: 'post',
          pathParams: { storeId, version: variables.version },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => invalidatePublishedViews(queryClient, storeId),
  })
}

export function useCancelReservationTimeSlotsPublication(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (
      variables: VersionVariables<{ changeReason: string }>,
    ): Promise<ReservationTimeSlotsData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/reservation-time-slots/{version}/publication-cancellation',
        {
          method: 'post',
          pathParams: { storeId, version: variables.version },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => invalidatePublishedViews(queryClient, storeId),
  })
}

export function useSaveRegularClosureDraft(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useMutation({
    mutationFn: async (
      variables: DraftVariables<{ weeklyDays: DayOfWeek[]; dates: string[] }>,
    ): Promise<RegularClosureData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/regular-closures',
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

export function usePublishRegularClosure(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (
      variables: VersionVariables<SchedulePublicationRequest>,
    ): Promise<RegularClosureData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/regular-closures/{version}/publication',
        {
          method: 'post',
          pathParams: { storeId, version: variables.version },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => invalidatePublishedViews(queryClient, storeId),
  })
}

export function useCancelRegularClosurePublication(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (
      variables: VersionVariables<{ changeReason: string }>,
    ): Promise<RegularClosureData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/regular-closures/{version}/publication-cancellation',
        {
          method: 'post',
          pathParams: { storeId, version: variables.version },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => invalidatePublishedViews(queryClient, storeId),
  })
}

export interface TemporaryClosureInput {
  startAt: string
  endAt: string
  reason: TemporaryClosureReason
  publicMessage?: string
}

export function useCreateTemporaryClosure(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (
      variables: DraftVariables<TemporaryClosureInput>,
    ): Promise<TemporaryClosureData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/temporary-closures',
        {
          method: 'post',
          pathParams: { storeId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => invalidatePublishedViews(queryClient, storeId),
  })
}

export function useChangeTemporaryClosureEndAt(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      closureId: number
      body: { endAt: string; changeReason: string }
      idempotencyKey: string
    }): Promise<TemporaryClosureData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/temporary-closures/{closureId}/end-at',
        {
          method: 'put',
          pathParams: { storeId, closureId: variables.closureId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => invalidatePublishedViews(queryClient, storeId),
  })
}

export function useCancelTemporaryClosure(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      closureId: number
      body: { changeReason: string }
      idempotencyKey: string
    }): Promise<TemporaryClosureData> => {
      const response = await apiClient(
        '/api/v1/store-operators/stores/{storeId}/temporary-closures/{closureId}/cancellation',
        {
          method: 'post',
          pathParams: { storeId, closureId: variables.closureId },
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: () => invalidatePublishedViews(queryClient, storeId),
  })
}
