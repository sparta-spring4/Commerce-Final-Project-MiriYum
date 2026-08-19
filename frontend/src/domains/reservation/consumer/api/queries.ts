import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CONSUMER_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/consumer/querySession'
import { publicApiClient } from '../../../../shared/api/publicApiClient'
import { useConsumerAuth } from '../../../account/consumer/auth'
import { consumerReservationHistoryKeys } from './historyQueries'
import { storeSearchKeys } from '../../../store/public/api/queries'
import type { ReservationCreateRequest, ReservationDetail } from '../model/draft'

export const reservationKeys = {
  // 뿌리는 shared가 소유한다. 세션 종료 정리가 같은 값을 보고 지운다.
  all: CONSUMER_PROTECTED_QUERY_ROOTS.reservations,
  detail: (reservationId: string) =>
    [...reservationKeys.all, reservationId] as const,
  menuHoldAvailability: (
    storeId: string,
    serviceDate: string,
    startTime: string,
  ) =>
    [
      ...reservationKeys.all,
      'menu-hold-availability',
      storeId,
      serviceDate,
      startTime,
    ] as const,
}

/**
 * 메뉴 홀드 가용성.
 *
 * 공개 조회다. 인증 shell의 client를 쓰면 비회원이 예약 화면을 미리 볼 때
 * 불필요한 401이 난다.
 *
 * 여기 보이는 수량은 미리보기다. 최종 확보는 예약 생성 쓰기에서 서버가 판정한다.
 */
export function useMenuHoldAvailability(
  storeId: string,
  serviceDate: string,
  startTime: string,
  enabled: boolean,
) {
  return useQuery({
    queryKey: reservationKeys.menuHoldAvailability(
      storeId,
      serviceDate,
      startTime,
    ),
    queryFn: async ({ signal }) => {
      const response = await publicApiClient(
        '/api/v1/stores/{storeId}/menu-hold-availability',
        {
          method: 'get',
          pathParams: { storeId },
          query: { serviceDate, startTime },
          signal,
        },
      )
      return response.data
    },
    enabled,
    // 재고는 계속 바뀐다. 오래된 값을 확정처럼 보여 주지 않는다.
    staleTime: 0,
  })
}

export function useReservation(reservationId: string) {
  const { apiClient } = useConsumerAuth()

  return useQuery({
    queryKey: reservationKeys.detail(reservationId),
    queryFn: async ({ signal }): Promise<ReservationDetail> => {
      const response = await apiClient(
        '/api/v1/consumers/me/reservations/{reservationId}',
        { method: 'get', pathParams: { reservationId }, signal },
      )
      return response.data
    },
  })
}

/**
 * 예약과 선택 메뉴 홀드를 한 번의 쓰기로 만든다.
 *
 * 성공 후 매장 검색 가용성·내 예약 목록·해당 상세 캐시를 무효화한다.
 * 무효화하지 않으면 방금 소진한 자리가 여전히 예약 가능으로 보인다.
 */
export function useCreateReservation() {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      body: ReservationCreateRequest
      idempotencyKey: string
    }): Promise<ReservationDetail> => {
      const response = await apiClient('/api/v1/consumers/me/reservations', {
        method: 'post',
        body: variables.body,
        idempotencyKey: variables.idempotencyKey,
      })
      return response.data
    },
    onSuccess: (reservation) => {
      void invalidateAfterReservationChange(queryClient, reservation)
    },
  })
}

export function useCancelReservation(reservationId: string) {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      reason?: string
      idempotencyKey: string
    }): Promise<ReservationDetail> => {
      const response = await apiClient(
        '/api/v1/consumers/me/reservations/{reservationId}/cancellations',
        {
          method: 'post',
          pathParams: { reservationId },
          body: variables.reason ? { reason: variables.reason } : {},
          idempotencyKey: variables.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (reservation) => {
      void invalidateAfterReservationChange(queryClient, reservation)
    },
  })
}

function invalidateAfterReservationChange(
  queryClient: ReturnType<typeof useQueryClient>,
  reservation: ReservationDetail,
): Promise<unknown> {
  queryClient.setQueryData(
    reservationKeys.detail(reservation.reservationId),
    reservation,
  )

  return Promise.all([
    // 검색 결과의 예약 가용성이 바뀐다.
    queryClient.invalidateQueries({ queryKey: storeSearchKeys.all }),
    // 내 예약 목록이 바뀐다.
    queryClient.invalidateQueries({
      queryKey: consumerReservationHistoryKeys.all,
    }),
    // 메뉴 홀드 잔여 수량이 바뀐다.
    queryClient.invalidateQueries({
      queryKey: [...reservationKeys.all, 'menu-hold-availability'],
    }),
  ])
}
