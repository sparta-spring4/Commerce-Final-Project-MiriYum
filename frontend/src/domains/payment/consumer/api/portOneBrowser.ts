import {
  requestPayment,
  type PaymentResponse,
} from '@portone/browser-sdk/v2'

export interface PortOneBrowserConfig {
  storeId: string
  channelKey: string
}

export interface DepositPaymentPreparation {
  portOnePaymentId: string
  orderName: string
  amountMinor: number
  currency: string
}

export class PortOneBrowserConfigError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PortOneBrowserConfigError'
  }
}

export function readPortOneBrowserConfig(): PortOneBrowserConfig {
  return {
    storeId: import.meta.env.MIRIYUM_PORTONE_STORE_ID ?? '',
    channelKey: import.meta.env.MIRIYUM_PORTONE_CHANNEL_KEY ?? '',
  }
}

export async function requestDepositPayment(
  preparation: DepositPaymentPreparation,
  config: PortOneBrowserConfig = readPortOneBrowserConfig(),
): Promise<PaymentResponse | undefined> {
  if (config.storeId.length === 0 || config.channelKey.length === 0) {
    throw new PortOneBrowserConfigError(
      'PortOne Store ID와 Channel Key를 로컬 환경에 설정해 주세요.',
    )
  }
  if (preparation.currency !== 'KRW') {
    throw new PortOneBrowserConfigError(
      `지원하지 않는 결제 통화입니다: ${preparation.currency}`,
    )
  }

  return requestPayment({
    storeId: config.storeId,
    channelKey: config.channelKey,
    paymentId: preparation.portOnePaymentId,
    orderName: preparation.orderName,
    totalAmount: preparation.amountMinor,
    currency: 'KRW',
    payMethod: 'CARD',
  })
}
