import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestPayment } from '@portone/browser-sdk/v2'
import {
  PortOneBrowserConfigError,
  requestDepositPayment,
} from './portOneBrowser'

vi.mock('@portone/browser-sdk/v2', () => ({
  requestPayment: vi.fn(),
}))

const requestPaymentMock = vi.mocked(requestPayment)

beforeEach(() => {
  requestPaymentMock.mockReset()
})

describe('PortOne 예약금 브라우저 어댑터', () => {
  it('백엔드가 준비한 식별자와 금액으로 토스 카드 결제를 요청한다', async () => {
    requestPaymentMock.mockResolvedValue({
      paymentId: 'payment-reservation-910000000000000001',
      transactionType: 'PAYMENT',
      txId: 'tx-test-1',
    })

    await requestDepositPayment(
      {
        portOnePaymentId: 'payment-reservation-910000000000000001',
        orderName: '파스타 마스터즈 예약금',
        amountMinor: 10000,
        currency: 'KRW',
      },
      {
        storeId: 'store-test-visible',
        channelKey: 'channel-key-test-visible',
      },
    )

    expect(requestPaymentMock).toHaveBeenCalledWith({
      storeId: 'store-test-visible',
      channelKey: 'channel-key-test-visible',
      paymentId: 'payment-reservation-910000000000000001',
      orderName: '파스타 마스터즈 예약금',
      totalAmount: 10000,
      currency: 'KRW',
      payMethod: 'CARD',
    })
  })

  it('브라우저 공개 설정이 없으면 결제창을 열지 않는다', async () => {
    await expect(
      requestDepositPayment(
        {
          portOnePaymentId: 'payment-reservation-910000000000000001',
          orderName: '파스타 마스터즈 예약금',
          amountMinor: 10000,
          currency: 'KRW',
        },
        { storeId: '', channelKey: '' },
      ),
    ).rejects.toBeInstanceOf(PortOneBrowserConfigError)
    expect(requestPaymentMock).not.toHaveBeenCalled()
  })
})
