import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CONSUMER_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/consumer/querySession'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import type { components } from '../../../../shared/api/generated/payment'

export type ConsumerPayment = components['schemas']['Payment']
export type ConsumerPaymentHistory = components['schemas']['PaymentHistorySlice']

export const consumerPaymentKeys = {
  all: [...CONSUMER_PROTECTED_QUERY_ROOTS.account, 'me', 'payments'] as const,
}

export function useConsumerPayments() {
  const { apiClient } = useConsumerAuth()
  return useQuery({
    queryKey: consumerPaymentKeys.all,
    queryFn: async ({ signal }): Promise<ConsumerPaymentHistory> => {
      const response = await apiClient('/api/v1/consumers/me/payments', {
        method: 'get', query: { size: 5 }, signal,
      })
      return response.data
    },
  })
}

export function useConfirmCurrentConsumerPayment(paymentId: string) {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (input: {
      portOnePaymentId: string
      idempotencyKey: string
    }): Promise<ConsumerPayment> => {
      const response = await apiClient(
        '/api/v1/consumers/me/payments/{paymentId}/confirmations',
        {
          method: 'post',
          pathParams: { paymentId },
          body: { portOnePaymentId: input.portOnePaymentId },
          idempotencyKey: input.idempotencyKey,
        },
      )
      return response.data
    },
    onSuccess: (payment) => {
      queryClient.setQueryData(
        [...consumerPaymentKeys.all, payment.paymentId],
        payment,
      )
      void queryClient.invalidateQueries({ queryKey: consumerPaymentKeys.all })
    },
  })
}
