import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ApiClient } from '../../../../../shared/api/client'
import { CONSUMER_PROTECTED_QUERY_ROOTS } from '../../../../../app/shells/consumer/querySession'
import type { components } from '../../../../../shared/api/generated/auth-account'
import { useConsumerAuth } from '../../auth'

export type ConsumerAccount = components['schemas']['ConsumerAccount']
export const consumerAccountKeys = {
  // 뿌리는 shared가 소유한다. 세션 종료 정리가 같은 값을 보고 지운다.
  all: CONSUMER_PROTECTED_QUERY_ROOTS.account,
  me: () => [...consumerAccountKeys.all, 'me'] as const,
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

export type { ApiClient }
