import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ConsumerAuthProvider } from '../../../account/consumer/auth'
import { successResponse } from '../../../../test/msw/envelope'
import { SUCCESS_CODE } from '../../../../shared/api/envelope'
import { server } from '../../../../test/msw/server'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import {
  RESERVATION_REQUEST_ID,
  reservationDetail,
  reservationRequest,
} from '../test/fixtures'
import { requestDepositPayment } from '../../../payment/consumer/api/portOneBrowser'
import { reservationKeys } from '../api/queries'
import { ReservationRequestPaymentPage } from './ReservationRequestPaymentPage'

vi.mock('../../../payment/consumer/api/portOneBrowser', () => ({
  requestDepositPayment: vi.fn(),
}))

const requestDepositPaymentMock = vi.mocked(requestDepositPayment)
const PAYMENT_ID = '910000000000000001'
const REQUEST_PATH =
  '/api/v1/consumers/me/reservation-requests/:reservationRequestId'
const CONFIRM_PATH =
  '/api/v1/consumers/me/payments/:paymentId/confirmations'
const FINALIZE_PATH =
  '/api/v1/consumers/me/reservation-requests/:reservationRequestId/finalizations'
const ABANDON_PATH =
  '/api/v1/consumers/me/reservation-requests/:reservationRequestId/abandonments'
const CONFIRMATION_HINT_STORAGE_KEY =
  `MIRIYUM_RESERVATION_PAYMENT_CONFIRMATION:${RESERVATION_REQUEST_ID}`
const PERSISTED_CONFIRMATION_KEY = '11111111-1111-4111-8111-111111111111'
const BACKEND_UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/

function LocationProbe() {
  const { pathname } = useLocation()
  return <p data-testid="location">{pathname}</p>
}

function renderPayment() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  const renderResult = render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter
          initialEntries={[
            `/reservation-requests/${RESERVATION_REQUEST_ID}/payment`,
          ]}
        >
          <Routes>
            <Route
              path="/reservation-requests/:reservationRequestId/payment"
              element={<ReservationRequestPaymentPage />}
            />
            <Route
              path="/reservations/:reservationId/complete"
              element={<LocationProbe />}
            />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </QueryClientProvider>,
  )

  return { ...renderResult, queryClient }
}

function payment(status: 'PAID' | 'READY' | 'RECONCILIATION_REQUIRED') {
  return {
    paymentId: PAYMENT_ID,
    reservationReferenceId: RESERVATION_REQUEST_ID,
    amountMinor: 10000,
    refundedAmountMinor: 0,
    refundableAmountMinor: status === 'PAID' ? 10000 : 0,
    currency: 'KRW',
    status,
    lastAttemptStatus: status === 'PAID' ? 'PAID' : 'UNKNOWN',
    createdAt: '2026-08-20T14:00:00+09:00',
    paidAt: status === 'PAID' ? '2026-08-20T14:01:00+09:00' : null,
    updatedAt: '2026-08-20T14:01:00+09:00',
    refunds: [],
  } as const
}

beforeEach(() => {
  requestDepositPaymentMock.mockReset()
  window.localStorage.clear()
})

describe('예약금 결제 화면', () => {
  it('서버가 PAID를 확인한 뒤 최종화하고 기존 완료 화면으로 이동한다', async () => {
    const commandOrder: string[] = []
    let confirmationKey: string | null = null
    let finalizationKey: string | null = null

    requestDepositPaymentMock.mockResolvedValue({
      transactionType: 'PAYMENT',
      txId: 'tx-test-1',
      paymentId: 'payment-reservation-910000000000000001',
    })
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => successResponse(reservationRequest())),
      http.post(CONFIRM_PATH, async ({ request }) => {
        commandOrder.push('confirm')
        confirmationKey = request.headers.get('Idempotency-Key')
        expect(await request.json()).toEqual({
          portOnePaymentId: 'payment-reservation-910000000000000001',
        })
        return successResponse(payment('PAID'))
      }),
      http.post(FINALIZE_PATH, async ({ request }) => {
        commandOrder.push('finalize')
        finalizationKey = request.headers.get('Idempotency-Key')
        expect(await request.json()).toEqual({})
        return successResponse(reservationDetail())
      }),
    )

    window.localStorage.setItem('unrelated', 'keep')
    renderPayment()
    fireEvent.click(
      await screen.findByRole('button', { name: '예약금 결제하기' }),
    )

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/reservations/01JBQ8Z4T7K2N9V6M3P5R8W1R1/complete',
      ),
    )
    expect(commandOrder).toEqual(['confirm', 'finalize'])
    expect(confirmationKey).toMatch(BACKEND_UUID_PATTERN)
    expect(finalizationKey).toMatch(BACKEND_UUID_PATTERN)
    expect(finalizationKey).not.toBe(confirmationKey)
    expect(localStorage.getItem(CONFIRMATION_HINT_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem('unrelated')).toBe('keep')
  })

  it('최초 미결제 진입에서는 SDK 성공 전에 confirmation 경로를 열지 않는다', async () => {
    let confirmationCalls = 0
    let resolvePayment: ((value: undefined) => void) | undefined
    requestDepositPaymentMock.mockReturnValue(
      new Promise((resolve) => {
        resolvePayment = resolve
      }),
    )
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => successResponse(reservationRequest())),
      http.post(CONFIRM_PATH, () => {
        confirmationCalls += 1
        return successResponse(payment('READY'))
      }),
    )

    renderPayment()
    const payButton = await screen.findByRole('button', {
      name: '예약금 결제하기',
    })

    expect(
      screen.queryByRole('button', { name: '결제 상태 다시 확인' }),
    ).not.toBeInTheDocument()
    fireEvent.click(payButton)
    await waitFor(() => expect(requestDepositPaymentMock).toHaveBeenCalledOnce())
    expect(confirmationCalls).toBe(0)

    resolvePayment?.(undefined)
    expect(
      await screen.findByText('결제창이 닫혔습니다. 결제를 다시 시도할 수 있습니다.'),
    ).toBeInTheDocument()
    expect(confirmationCalls).toBe(0)
  })

  it('SDK 취소는 요청을 포기하지 않고 다시 결제할 수 있게 한다', async () => {
    let confirmationCalls = 0
    requestDepositPaymentMock.mockResolvedValue({
      transactionType: 'PAYMENT',
      txId: 'tx-test-cancel',
      paymentId: 'payment-reservation-910000000000000001',
      code: 'FAILURE_TYPE_PG',
      message: '사용자가 결제를 취소했습니다.',
    })
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => successResponse(reservationRequest())),
      http.post(CONFIRM_PATH, () => {
        confirmationCalls += 1
        return successResponse(payment('READY'))
      }),
    )

    renderPayment()
    fireEvent.click(
      await screen.findByRole('button', { name: '예약금 결제하기' }),
    )

    expect(
      await screen.findByText('사용자가 결제를 취소했습니다.'),
    ).toBeInTheDocument()
    expect(confirmationCalls).toBe(0)
    expect(
      screen.getByRole('button', { name: '예약금 결제하기' }),
    ).toBeEnabled()
  })

  it.each([
    {
      changedState: '포기 의사가 기록된 상태',
      request: reservationRequest({ abandonmentRequested: true }),
      message:
        '예약 요청 포기가 접수되어 이 요청으로는 더 이상 결제할 수 없습니다.',
    },
    {
      changedState: '만료 상태',
      request: reservationRequest({ status: 'EXPIRED' }),
      message: '결제 가능 시간이 지난 예약 요청입니다.',
    },
  ])(
    '결제 오류 뒤 $changedState로 갱신되면 재시도로 결제를 반복하지 않는다',
    async ({ request, message }) => {
      let confirmationCalls = 0
      requestDepositPaymentMock.mockRejectedValue(
        new Error('PortOne SDK를 실행할 수 없습니다.'),
      )
      server.use(
        authenticatedConsumer(),
        http.get(REQUEST_PATH, () => successResponse(reservationRequest())),
        http.post(CONFIRM_PATH, () => {
          confirmationCalls += 1
          return successResponse(payment('PAID'))
        }),
      )

      const { queryClient } = renderPayment()
      fireEvent.click(
        await screen.findByRole('button', { name: '예약금 결제하기' }),
      )
      expect(
        await screen.findByText(
          '결제 처리 중 문제가 발생했습니다. 서버 상태를 다시 확인해 주세요.',
        ),
      ).toBeInTheDocument()

      act(() => {
        queryClient.setQueryData(
          reservationKeys.request(RESERVATION_REQUEST_ID),
          request,
        )
      })

      expect(await screen.findByText(message)).toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: '예약금 결제하기' }),
      ).toBeDisabled()
      fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

      await waitFor(() =>
        expect(
          screen.queryByText(
            '결제 처리 중 문제가 발생했습니다. 서버 상태를 다시 확인해 주세요.',
          ),
        ).not.toBeInTheDocument(),
      )
      expect(requestDepositPaymentMock).toHaveBeenCalledTimes(1)
      expect(confirmationCalls).toBe(0)
    },
  )

  it('결제창 성공 뒤 확인 실패와 새로고침 후에도 같은 키로 복구한다', async () => {
    const confirmationKeys: string[] = []
    let confirmationCalls = 0

    requestDepositPaymentMock.mockResolvedValue({
      transactionType: 'PAYMENT',
      txId: 'tx-test-retry',
      paymentId: 'payment-reservation-910000000000000001',
    })
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => successResponse(reservationRequest())),
      http.post(CONFIRM_PATH, ({ request }) => {
        confirmationCalls += 1
        confirmationKeys.push(request.headers.get('Idempotency-Key') ?? '')
        if (confirmationCalls === 1) {
          return HttpResponse.json(
            {
              code: 'COMMON_012',
              message: '서비스를 일시적으로 사용할 수 없습니다.',
            },
            { status: 503 },
          )
        }
        return successResponse(payment('PAID'))
      }),
      http.post(FINALIZE_PATH, () => successResponse(reservationDetail())),
    )

    const firstRender = renderPayment()
    fireEvent.click(
      await screen.findByRole('button', { name: '예약금 결제하기' }),
    )

    expect(
      await screen.findByText(
        '결제 처리 중 문제가 발생했습니다. 서버 상태를 다시 확인해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(confirmationKeys).toHaveLength(1)
    expect(confirmationKeys[0]).toMatch(BACKEND_UUID_PATTERN)
    expect(localStorage.getItem(CONFIRMATION_HINT_STORAGE_KEY)).toBe(
      confirmationKeys[0],
    )

    firstRender.unmount()
    renderPayment()

    fireEvent.click(
      await screen.findByRole('button', { name: '결제 상태 다시 확인' }),
    )

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/reservations/01JBQ8Z4T7K2N9V6M3P5R8W1R1/complete',
      ),
    )
    expect(requestDepositPaymentMock).toHaveBeenCalledTimes(1)
    expect(confirmationKeys).toHaveLength(2)
    expect(confirmationKeys[1]).toBe(confirmationKeys[0])
    expect(localStorage.getItem(CONFIRMATION_HINT_STORAGE_KEY)).toBeNull()
  })

  it('복구 confirmation 오류의 다시 시도는 같은 confirmation과 키를 반복한다', async () => {
    const confirmationKeys: string[] = []
    let confirmationCalls = 0
    let requestCalls = 0
    localStorage.setItem(
      CONFIRMATION_HINT_STORAGE_KEY,
      PERSISTED_CONFIRMATION_KEY,
    )
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => {
        requestCalls += 1
        return successResponse(reservationRequest())
      }),
      http.post(CONFIRM_PATH, ({ request }) => {
        confirmationCalls += 1
        confirmationKeys.push(request.headers.get('Idempotency-Key') ?? '')
        if (confirmationCalls === 1) {
          return HttpResponse.json(
            {
              code: 'COMMON_011',
              message: '결제 확인 처리 중 오류가 발생했습니다.',
            },
            { status: 500 },
          )
        }
        return successResponse(payment('PAID'))
      }),
      http.post(FINALIZE_PATH, () => successResponse(reservationDetail())),
    )

    renderPayment()
    fireEvent.click(
      await screen.findByRole('button', { name: '결제 상태 다시 확인' }),
    )
    fireEvent.click(
      await screen.findByRole('button', { name: '다시 시도' }),
    )

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/reservations/01JBQ8Z4T7K2N9V6M3P5R8W1R1/complete',
      ),
    )
    expect(requestDepositPaymentMock).not.toHaveBeenCalled()
    expect(confirmationKeys).toEqual([
      PERSISTED_CONFIRMATION_KEY,
      PERSISTED_CONFIRMATION_KEY,
    ])
    expect(requestCalls).toBe(1)
  })

  it('유효한 UUID가 아닌 복구 힌트는 제거하고 결제부터 다시 시작한다', async () => {
    localStorage.setItem(CONFIRMATION_HINT_STORAGE_KEY, 'not-a-uuid')
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => successResponse(reservationRequest())),
    )

    renderPayment()

    expect(
      await screen.findByRole('button', { name: '예약금 결제하기' }),
    ).toBeEnabled()
    expect(
      screen.queryByRole('button', { name: '결제 상태 다시 확인' }),
    ).not.toBeInTheDocument()
    expect(localStorage.getItem(CONFIRMATION_HINT_STORAGE_KEY)).toBeNull()
  })

  it('결제 확인이 202이면 예약을 최종화하지 않는다', async () => {
    let finalizationCalls = 0
    requestDepositPaymentMock.mockResolvedValue({
      transactionType: 'PAYMENT',
      txId: 'tx-test-reconciliation',
      paymentId: 'payment-reservation-910000000000000001',
    })
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => successResponse(reservationRequest())),
      http.post(CONFIRM_PATH, () =>
        HttpResponse.json(
          {
            code: SUCCESS_CODE,
            message: '결제 결과를 확인 중입니다.',
            data: payment('RECONCILIATION_REQUIRED'),
          },
          { status: 202 },
        ),
      ),
      http.post(FINALIZE_PATH, () => {
        finalizationCalls += 1
        return successResponse(reservationDetail())
      }),
    )

    renderPayment()
    fireEvent.click(
      await screen.findByRole('button', { name: '예약금 결제하기' }),
    )

    expect(
      await screen.findByText(
        '결제 결과를 확인 중입니다. 잠시 후 상태를 다시 확인해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(finalizationCalls).toBe(0)
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()
  })

  it('최종화가 202이면 완료로 표시하지 않고 최신 요청 상태를 유지한다', async () => {
    let latest = reservationRequest()
    requestDepositPaymentMock.mockResolvedValue({
      transactionType: 'PAYMENT',
      txId: 'tx-test-finalizing',
      paymentId: 'payment-reservation-910000000000000001',
    })
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => successResponse(latest)),
      http.post(CONFIRM_PATH, () => successResponse(payment('PAID'))),
      http.post(FINALIZE_PATH, () => {
        latest = reservationRequest({ status: 'FINALIZING_RESOURCES' })
        return HttpResponse.json(
          {
            code: SUCCESS_CODE,
            message: '예약 확정을 처리 중입니다.',
            data: latest,
          },
          { status: 202 },
        )
      }),
    )

    renderPayment()
    fireEvent.click(
      await screen.findByRole('button', { name: '예약금 결제하기' }),
    )

    expect(
      await screen.findByText('결제 확인 후 예약 자원을 확정하고 있습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '결제 상태 다시 확인' }),
    ).toBeDisabled()
  })

  it('사용자가 직접 포기한 경우에만 abandonment 명령을 보낸다', async () => {
    let abandonmentKey: string | null = null
    localStorage.setItem(
      CONFIRMATION_HINT_STORAGE_KEY,
      PERSISTED_CONFIRMATION_KEY,
    )
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => successResponse(reservationRequest())),
      http.post(ABANDON_PATH, async ({ request }) => {
        abandonmentKey = request.headers.get('Idempotency-Key')
        expect(await request.json()).toEqual({})
        return successResponse(
          reservationRequest({ status: 'ABANDONED', abandonmentRequested: true }),
        )
      }),
    )

    renderPayment()
    await screen.findByRole('button', { name: '결제 상태 다시 확인' })
    expect(abandonmentKey).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '예약 요청 포기' }))

    await waitFor(() =>
      expect(abandonmentKey).toMatch(BACKEND_UUID_PATTERN),
    )
    expect(await screen.findByText(/포기된 예약 요청/)).toBeInTheDocument()
    expect(requestDepositPaymentMock).not.toHaveBeenCalled()
    expect(localStorage.getItem(CONFIRMATION_HINT_STORAGE_KEY)).toBeNull()
  })

  it('포기 명령이 202로 포기 의사를 기록하면 결제를 다시 허용하지 않는다', async () => {
    let confirmationCalls = 0
    localStorage.setItem(
      CONFIRMATION_HINT_STORAGE_KEY,
      PERSISTED_CONFIRMATION_KEY,
    )
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () => successResponse(reservationRequest())),
      http.post(CONFIRM_PATH, () => {
        confirmationCalls += 1
        return successResponse(payment('PAID'))
      }),
      http.post(ABANDON_PATH, () =>
        HttpResponse.json(
          {
            code: SUCCESS_CODE,
            message: '예약 요청 포기 처리를 진행 중입니다.',
            data: reservationRequest({ abandonmentRequested: true }),
          },
          { status: 202 },
        ),
      ),
    )

    renderPayment()
    await screen.findByRole('button', { name: '결제 상태 다시 확인' })
    fireEvent.click(screen.getByRole('button', { name: '예약 요청 포기' }))

    expect(
      await screen.findByText(
        '예약 요청 포기가 접수되어 이 요청으로는 더 이상 결제할 수 없습니다.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '예약금 결제하기' }),
    ).toBeDisabled()
    expect(
      screen.queryByRole('button', { name: '예약 요청 포기' }),
    ).not.toBeInTheDocument()
    expect(requestDepositPaymentMock).not.toHaveBeenCalled()
    expect(confirmationCalls).toBe(0)
    expect(localStorage.getItem(CONFIRMATION_HINT_STORAGE_KEY)).toBeNull()
  })

  it('포기 의사가 기록된 요청으로 재진입하면 confirmation 힌트를 제거한다', async () => {
    let confirmationCalls = 0
    localStorage.setItem(
      CONFIRMATION_HINT_STORAGE_KEY,
      PERSISTED_CONFIRMATION_KEY,
    )
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () =>
        successResponse(reservationRequest({ abandonmentRequested: true })),
      ),
      http.post(CONFIRM_PATH, () => {
        confirmationCalls += 1
        return successResponse(payment('PAID'))
      }),
    )

    renderPayment()

    expect(
      await screen.findByRole('button', { name: '예약금 결제하기' }),
    ).toBeDisabled()
    expect(requestDepositPaymentMock).not.toHaveBeenCalled()
    expect(confirmationCalls).toBe(0)
    await waitFor(() =>
      expect(localStorage.getItem(CONFIRMATION_HINT_STORAGE_KEY)).toBeNull(),
    )
  })

  it('종결된 요청에서는 결제창을 열지 않는다', async () => {
    localStorage.setItem(
      CONFIRMATION_HINT_STORAGE_KEY,
      PERSISTED_CONFIRMATION_KEY,
    )
    server.use(
      authenticatedConsumer(),
      http.get(REQUEST_PATH, () =>
        successResponse(
          reservationRequest({ status: 'ABANDONED', abandonmentRequested: true }),
        ),
      ),
    )

    renderPayment()

    expect(
      await screen.findByRole('button', { name: '예약금 결제하기' }),
    ).toBeDisabled()
    expect(screen.getByText(/포기된 예약 요청/)).toBeInTheDocument()
    expect(requestDepositPaymentMock).not.toHaveBeenCalled()
    await waitFor(() =>
      expect(localStorage.getItem(CONFIRMATION_HINT_STORAGE_KEY)).toBeNull(),
    )
  })
})
