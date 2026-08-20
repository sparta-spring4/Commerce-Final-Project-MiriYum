import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { ConsumerAuthProvider } from '../../../account/consumer/auth'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import type { PaymentGateway } from '../api/paymentGateway'
import { ReservationPaymentPage } from './ReservationPaymentPage'

const REQUEST_ID = 'request-701'
const PAYMENT_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1P1'
const PORTONE_PAYMENT_ID = 'payment-reservation-701'

const reservationRequest = {
  reservationRequestId: REQUEST_ID,
  status: 'AWAITING_PAYMENT',
  expiresAt: '2026-09-01T10:00:00+09:00',
  paymentPreparation: {
    paymentId: PAYMENT_ID,
    portOnePaymentId: PORTONE_PAYMENT_ID,
    orderName: '카페 에비뉴 예약금',
    amountMinor: 10000,
    currency: 'KRW',
    sourceExpiresAt: '2026-09-01T10:00:00+09:00',
    status: 'READY',
  },
  abandonmentRequested: false,
  reservation: null,
} as const

function LocationProbe() {
  return <p data-testid="location">{useLocation().pathname}</p>
}

function renderPage(gateway: PaymentGateway, configured = true) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[`/reservation-requests/${REQUEST_ID}/payment`]}>
          <Routes>
            <Route
              path={CONSUMER_PATHS.reservationPayment}
              element={
                <ReservationPaymentPage
                  gateway={gateway}
                  portOneConfig={configured ? { storeId: 'store-portone', channelKey: 'channel-card' } : null}
                />
              }
            />
            <Route path={CONSUMER_PATHS.reservationComplete} element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </QueryClientProvider>,
  )
}

describe('예약금 결제 화면', () => {
  it('서버가 준비한 금액만 표시하고 결제 성공 뒤 서버 확정과 예약 확정을 순서대로 호출한다', async () => {
    const gateway: PaymentGateway = {
      requestPayment: vi.fn().mockResolvedValue({ kind: 'success', paymentId: PORTONE_PAYMENT_ID }),
    }
    const calls: string[] = []
    let confirmationBody: unknown = null

    server.use(
      authenticatedConsumer(),
      http.get(`/api/v1/consumers/me/reservation-requests/${REQUEST_ID}`, () =>
        successResponse(reservationRequest),
      ),
      http.post(`/api/v1/consumers/me/payments/${PAYMENT_ID}/confirmations`, async ({ request }) => {
        calls.push('confirmation')
        confirmationBody = await request.json()
        expect(request.headers.get('Idempotency-Key')).toMatch(/[0-9a-f-]{36}/)
        return successResponse({
          paymentId: PAYMENT_ID,
          reservationReferenceId: REQUEST_ID,
          amountMinor: 10000,
          refundedAmountMinor: 0,
          refundableAmountMinor: 10000,
          currency: 'KRW',
          status: 'PAID',
          lastAttemptStatus: 'PAID',
          createdAt: '2026-09-01T09:00:00+09:00',
          paidAt: '2026-09-01T09:01:00+09:00',
          updatedAt: '2026-09-01T09:01:00+09:00',
          refunds: [],
        })
      }),
      http.post(`/api/v1/consumers/me/reservation-requests/${REQUEST_ID}/finalizations`, ({ request }) => {
        calls.push('finalization')
        expect(request.headers.get('Idempotency-Key')).toMatch(/[0-9a-f-]{36}/)
        return successResponse({
          reservationId: 'reservation-901',
          storeId: 'store-101',
          status: 'CONFIRMED',
          serviceDate: '2026-09-01',
          startTime: '19:00',
          endTime: '20:30',
          party: { adultCount: 2, childCount: 0, infantCount: 0 },
          menuHold: null,
        })
      }),
    )

    renderPage(gateway)

    expect(await screen.findByText('10,000원')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '10,000원 결제하기' }))

    await waitFor(() => expect(gateway.requestPayment).toHaveBeenCalledWith({
      storeId: 'store-portone',
      channelKey: 'channel-card',
      paymentId: PORTONE_PAYMENT_ID,
      orderName: '카페 에비뉴 예약금',
      totalAmount: 10000,
      currency: 'KRW',
    }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/reservations/reservation-901/complete'))
    expect(confirmationBody).toEqual({ portOnePaymentId: PORTONE_PAYMENT_ID })
    expect(calls).toEqual(['confirmation', 'finalization'])
  })

  it('결제창 실패는 서버 결제 확정을 호출하지 않고 다시 시도할 수 있게 한다', async () => {
    const gateway: PaymentGateway = {
      requestPayment: vi.fn().mockResolvedValue({ kind: 'failed', message: '사용자가 결제를 취소했습니다.' }),
    }
    let confirmations = 0
    server.use(
      authenticatedConsumer(),
      http.get(`/api/v1/consumers/me/reservation-requests/${REQUEST_ID}`, () => successResponse(reservationRequest)),
      http.post(`/api/v1/consumers/me/payments/${PAYMENT_ID}/confirmations`, () => {
        confirmations += 1
        return successResponse({})
      }),
    )

    renderPage(gateway)
    fireEvent.click(await screen.findByRole('button', { name: '10,000원 결제하기' }))

    expect(await screen.findByText('사용자가 결제를 취소했습니다.')).toBeInTheDocument()
    expect(confirmations).toBe(0)
    expect(screen.getByRole('button', { name: '다시 결제하기' })).toBeInTheDocument()
  })

  it('PortOne 설정이 없으면 결제 버튼을 잠그고 성공을 가장하지 않는다', async () => {
    const gateway: PaymentGateway = { requestPayment: vi.fn() }
    server.use(
      authenticatedConsumer(),
      http.get(`/api/v1/consumers/me/reservation-requests/${REQUEST_ID}`, () => successResponse(reservationRequest)),
    )

    renderPage(gateway, false)

    expect(await screen.findByText('결제 서비스 설정이 아직 완료되지 않았습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '결제 준비 중' })).toBeDisabled()
    expect(gateway.requestPayment).not.toHaveBeenCalled()
  })
})
