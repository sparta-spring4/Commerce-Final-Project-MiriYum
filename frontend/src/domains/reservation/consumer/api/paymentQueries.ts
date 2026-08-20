import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { useConsumerAuth } from '../../../account/consumer/auth'
import type { components as PaymentComponents } from '../../../../shared/api/generated/payment'
import {
  isReservationRequest,
  type ReservationCreateResult,
  type ReservationRequest,
} from '../model/draft'
import { reservationKeys } from './queries'

export type Payment = PaymentComponents['schemas']['Payment']

export const reservationPaymentKeys = {
  request: (reservationRequestId: string) =>
    [...reservationKeys.all, 'request', reservationRequestId] as const,
}

export function useReservationRequest(reservationRequestId: string) {
  const { apiClient } = useConsumerAuth()
  return useQuery({
    queryKey: reservationPaymentKeys.request(reservationRequestId),
    queryFn: async ({ signal }): Promise<ReservationRequest> => {
      const response = await apiClient(
        '/api/v1/consumers/me/reservation-requests/{reservationRequestId}',
        { method: 'get', pathParams: { reservationRequestId }, signal },
      )
      return response.data
    },
    enabled: reservationRequestId.length > 0,
  })
}

export function useConfirmReservationPayment() {
  const { apiClient } = useConsumerAuth()
  return useMutation({
    mutationFn: async (input: {
      paymentId: string
      portOnePaymentId: string
    }): Promise<Payment> => {
      const response = await apiClient(
        '/api/v1/consumers/me/payments/{paymentId}/confirmations',
        {
          method: 'post',
          pathParams: { paymentId: input.paymentId },
          body: { portOnePaymentId: input.portOnePaymentId },
          idempotencyKey: createIdempotencyKey(),
        },
      )
      // 200과 202 모두 같은 Payment 공개 모양이며 status가 결과를 구분한다.
      return response.data as unknown as Payment
    },
  })
}

export function useFinalizeReservationRequest(reservationRequestId: string) {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (): Promise<ReservationCreateResult> => {
      const response = await apiClient(
        '/api/v1/consumers/me/reservation-requests/{reservationRequestId}/finalizations',
        {
          method: 'post',
          pathParams: { reservationRequestId },
          body: {},
          idempotencyKey: createIdempotencyKey(),
        },
      )
      return response.data as unknown as ReservationCreateResult
    },
    onSuccess: (result) => {
      if (isReservationRequest(result)) {
        queryClient.setQueryData(
          reservationPaymentKeys.request(reservationRequestId),
          result,
        )
      }
    },
  })
}
