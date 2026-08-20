import { requestPayment } from '@portone/browser-sdk/v2'
import type { PaymentCurrency } from '@portone/browser-sdk/v2'

export interface PaymentGatewayInput {
  storeId: string
  channelKey: string
  paymentId: string
  orderName: string
  totalAmount: number
  currency: string
}

export type PaymentGatewayResult =
  | { kind: 'success'; paymentId: string }
  | { kind: 'failed'; message: string }

export interface PaymentGateway {
  requestPayment(input: PaymentGatewayInput): Promise<PaymentGatewayResult>
}

/** PortOne 브라우저 응답은 시작 신호일 뿐이며 최종 성공 판정은 backend가 한다. */
export const portOnePaymentGateway: PaymentGateway = {
  async requestPayment(input) {
    const response = await requestPayment({
      storeId: input.storeId,
      channelKey: input.channelKey,
      paymentId: input.paymentId,
      orderName: input.orderName,
      totalAmount: input.totalAmount,
      currency: input.currency as PaymentCurrency,
      payMethod: 'CARD',
      redirectUrl: window.location.href,
    })

    if (response === undefined) {
      return { kind: 'failed', message: '결제창이 닫혔습니다. 결제를 다시 시도해 주세요.' }
    }
    if (response.code !== undefined) {
      return {
        kind: 'failed',
        message: response.message ?? '결제를 완료하지 못했습니다.',
      }
    }
    if (response.paymentId !== input.paymentId) {
      return { kind: 'failed', message: '결제 결과를 확인하지 못했습니다.' }
    }
    return { kind: 'success', paymentId: response.paymentId }
  },
}
