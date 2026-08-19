import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CONSUMER_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/consumer/querySession'
import { publicApiClient } from '../../../../shared/api/publicApiClient'
import { useConsumerAuth } from '../../../account/consumer/auth'
import { storeSearchKeys } from '../../../store/public/api/queries'
import type {
  PickupReservation,
  PickupReservationCreateRequest,
} from '../model/pickup'

export const pickupKeys = {
  // 뿌리는 shared가 소유한다. 세션 종료 정리가 같은 값을 보고 지운다.
  all: CONSUMER_PROTECTED_QUERY_ROOTS.pickupReservations,
  availability: (storeId: string, pickupDate: string) =>
    [...pickupKeys.all, 'availability', storeId, pickupDate] as const,
  detail: (pickupReservationId: string) =>
    [...pickupKeys.all, pickupReservationId] as const,
}

/** 픽업 가용성. 공개 조회다. */
export function usePickupAvailability(
  storeId: string,
  pickupDate: string,
  enabled: boolean,
) {
  return useQuery({
    queryKey: pickupKeys.availability(storeId, pickupDate),
    queryFn: async ({ signal }) => {
      const response = await publicApiClient(
        '/api/v1/stores/{storeId}/pickup-availability',
        {
          method: 'get',
          pathParams: { storeId },
          query: { pickupDate },
          signal,
        },
      )
      return response.data
    },
    enabled,
    staleTime: 0,
  })
}

export function usePickupReservation(pickupReservationId: string) {
  const { apiClient } = useConsumerAuth()

  return useQuery({
    queryKey: pickupKeys.detail(pickupReservationId),
    queryFn: async ({ signal }): Promise<PickupReservation> => {
      const response = await apiClient(
        '/api/v1/consumers/me/pickup-reservations/{pickupReservationId}',
        { method: 'get', pathParams: { pickupReservationId }, signal },
      )
      return response.data
    },
  })
}

export function useCreatePickupReservation() {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: PickupReservationCreateRequest
      idempotencyKey: string
    }): Promise<PickupReservation> => {
      const response = await apiClient(
        '/api/v1/consumers/me/pickup-reservations',
        {
          method: 'post',
          body: variables.body,
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (reservation) =>
      void invalidateAfterPickupChange(queryClient, reservation),
  })
}

export function useCancelPickupReservation(pickupReservationId: string) {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      reason?: string
      idempotencyKey: string
    }): Promise<PickupReservation> => {
      const response = await apiClient(
        '/api/v1/consumers/me/pickup-reservations/{pickupReservationId}/cancellations',
        {
          method: 'post',
          pathParams: { pickupReservationId },
          body: variables.reason ? { reason: variables.reason } : {},
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (reservation) =>
      void invalidateAfterPickupChange(queryClient, reservation),
  })
}

/**
 * 픽업 변경 후 무효화 범위.
 *
 * 픽업은 일반 예약 수용량을 쓰지 않는다. 일반 예약 캐시를 픽업 성공으로
 * 고치지 않고, 메뉴 재고를 공유하는 매장 조회와 픽업 가용성만 다시 읽는다.
 */
function invalidateAfterPickupChange(
  queryClient: ReturnType<typeof useQueryClient>,
  reservation: PickupReservation,
): Promise<unknown> {
  queryClient.setQueryData(
    pickupKeys.detail(reservation.pickupReservationId),
    reservation,
  )

  return Promise.all([
    queryClient.invalidateQueries({
      queryKey: [...pickupKeys.all, 'availability'],
    }),
    // 매장 상세의 메뉴 판매 상태가 재고와 함께 바뀔 수 있다.
    queryClient.invalidateQueries({ queryKey: storeSearchKeys.all }),
  ])
}
