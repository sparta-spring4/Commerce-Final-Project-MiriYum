import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ApiClient } from '../../../shared/api/client'
import {
  CONSUMER_PROTECTED_QUERY_ROOTS,
  keepsSameListConditions,
} from '../../../shared/api/consumerSession'
import type { components } from '../../../shared/api/generated/auth-account'
import { useConsumerAuth } from '../../auth'
import type {
  ReservationHistoryStatus,
  ReservationSort,
} from '../model/reservationDisplay'

export type ConsumerAccount = components['schemas']['ConsumerAccount']

export const consumerAccountKeys = {
  // 뿌리는 shared가 소유한다. 세션 종료 정리가 같은 값을 보고 지운다.
  all: CONSUMER_PROTECTED_QUERY_ROOTS.account,
  me: () => [...consumerAccountKeys.all, 'me'] as const,
  reservations: (query: ReservationHistoryQuery) =>
    [...consumerAccountKeys.all, 'me', 'reservations', query] as const,
}

export interface ReservationHistoryQuery {
  status?: ReservationHistoryStatus
  page: number
  sort: ReservationSort
}

export function useConsumerAccount() {
  const { apiClient } = useConsumerAuth()

  return useQuery({
    queryKey: consumerAccountKeys.me(),
    queryFn: async ({ signal }): Promise<ConsumerAccount> => {
      const response = await apiClient('/api/v1/consumers/me', {
        method: 'get',
        signal,
      })
      return response.data
    },
  })
}

export function useMyReservations(query: ReservationHistoryQuery) {
  const { apiClient } = useConsumerAuth()

  return useQuery({
    queryKey: consumerAccountKeys.reservations(query),
    queryFn: async ({ signal }) => {
      const response = await apiClient('/api/v1/consumers/me/reservations', {
        method: 'get',
        query: {
          status: query.status,
          page: query.page > 0 ? query.page : undefined,
          sort: query.sort,
        },
        signal,
      })
      return response.data
    },
    /*
     * 페이지를 넘길 때만 이전 목록을 유지한다.
     *
     * 상태 필터가 바뀔 때도 유지하면 "방문 완료"를 눌러 놓고 이전 필터의
     * 예약이 계속 보이고, 그 카드로 상세까지 들어갈 수 있다.
     */
    placeholderData: (previous, previousQuery) =>
      keepsSameListConditions(previousQuery?.queryKey, query)
        ? previous
        : undefined,
  })
}

/**
 * 닉네임 변경.
 *
 * 멱등 키는 호출자가 만들어 넘긴다. 여기서 매번 생성하면 같은 사용자 의도의
 * 재시도가 새 키를 받아 backend가 중복 실행을 막지 못한다.
 */
export function useUpdateNickname() {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      nickname: string
      idempotencyKey: string
    }) => {
      const response = await apiClient('/api/v1/consumers/me', {
        method: 'patch',
        body: { nickname: variables.nickname },
        idempotencyKey: variables.idempotencyKey,
      })
      return response.data
    },
    onSuccess: (account) => {
      queryClient.setQueryData(consumerAccountKeys.me(), account)
    },
  })
}

/**
 * 최초 연락처 등록.
 *
 * 등록된 번호를 다른 번호로 바꾸는 용도로 재사용하지 않는다. 계약이 변경을
 * `ACCOUNT_007`로 거절하고, 1차 MVP에 연락처 변경 폼은 없다.
 */
export function useRegisterContact() {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (variables: {
      phoneNumber: string
      idempotencyKey: string
    }) => {
      const response = await apiClient('/api/v1/consumers/me/contact', {
        method: 'put',
        body: { phoneNumber: variables.phoneNumber },
        idempotencyKey: variables.idempotencyKey,
      })
      return response.data
    },
    onSuccess: (account) => {
      queryClient.setQueryData(consumerAccountKeys.me(), account)
    },
  })
}

/** 예약을 만들거나 취소한 뒤 내 예약 목록 캐시를 무효화한다. */
export function invalidateMyReservations(
  queryClient: ReturnType<typeof useQueryClient>,
): Promise<void> {
  return queryClient.invalidateQueries({
    queryKey: [...consumerAccountKeys.all, 'me', 'reservations'],
  })
}

export type { ApiClient }
