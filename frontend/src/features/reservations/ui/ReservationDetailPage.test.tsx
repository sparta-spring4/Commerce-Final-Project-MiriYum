import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { ConsumerAuthProvider } from '../../auth'
import { authenticatedConsumer } from '../../auth/test/handlers'
import { ReservationErrorCode } from '../model/errors'
import {
  MENU_ID,
  RESERVATION_CANCEL_PATH,
  RESERVATION_DETAIL_PATH,
  RESERVATION_ID,
  reservationDetail,
} from '../test/fixtures'
import { ReservationDetailPage } from './ReservationDetailPage'

function renderDetail() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[`/reservations/${RESERVATION_ID}`]}>
          <Routes>
            <Route
              path={ROUTES.reservationDetail}
              element={<ReservationDetailPage />}
            />
            <Route path={ROUTES.myReservations} element={<p>내 예약 목록</p>} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </QueryClientProvider>,
  )
}

function respondWith(detail: ReturnType<typeof reservationDetail>) {
  server.use(
    authenticatedConsumer(),
    http.get(RESERVATION_DETAIL_PATH, () => successResponse(detail)),
  )
}

describe('예약 상세 화면', () => {
  it('예약 내용을 표시한다', async () => {
    respondWith(reservationDetail())

    renderDetail()

    expect(
      await screen.findByRole('heading', { level: 1, name: '파스타 마스터즈' }),
    ).toBeInTheDocument()
    expect(screen.getByText('예약 확정')).toBeInTheDocument()
    expect(screen.getByText(/총 2명/)).toBeInTheDocument()
  })

  it('미리 선택한 메뉴와 금액을 표시한다', async () => {
    respondWith(
      reservationDetail({
        menuSelections: [
          {
            menuId: MENU_ID,
            menuName: '트러플 크림 파파델레',
            unitPrice: 32000,
            quantity: 2,
          },
        ],
      }),
    )

    renderDetail()

    expect(
      await screen.findByText('트러플 크림 파파델레 x 2 · 64,000원'),
    ).toBeInTheDocument()
  })

  it('확정 상태에서만 취소 버튼을 보여 준다', async () => {
    respondWith(reservationDetail({ status: 'FULFILLED' }))

    renderDetail()

    await screen.findByRole('heading', { level: 1 })
    expect(
      screen.queryByRole('button', { name: '예약 취소하기' }),
    ).not.toBeInTheDocument()
  })

  it('취소는 Idempotency-Key를 붙여 보낸다', async () => {
    let idempotencyKey: string | null = null
    let body: unknown = null

    server.use(
      authenticatedConsumer(),
      http.get(RESERVATION_DETAIL_PATH, () =>
        successResponse(reservationDetail()),
      ),
      http.post(RESERVATION_CANCEL_PATH, async ({ request }) => {
        idempotencyKey = request.headers.get('Idempotency-Key')
        body = await request.json()
        return successResponse(
          reservationDetail({ status: 'CANCELLED', cancelledBy: 'CONSUMER' }),
        )
      }),
    )

    renderDetail()

    fireEvent.click(await screen.findByRole('button', { name: '예약 취소하기' }))
    fireEvent.change(screen.getByLabelText('취소 사유 (선택)'), {
      target: { value: '일정이 바뀌었습니다.' },
    })
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    await waitFor(() => expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/))
    expect(body).toEqual({ reason: '일정이 바뀌었습니다.' })
  })

  it('사유 없이 취소하면 reason을 보내지 않는다', async () => {
    let body: unknown = null

    server.use(
      authenticatedConsumer(),
      http.get(RESERVATION_DETAIL_PATH, () =>
        successResponse(reservationDetail()),
      ),
      http.post(RESERVATION_CANCEL_PATH, async ({ request }) => {
        body = await request.json()
        return successResponse(reservationDetail({ status: 'CANCELLED' }))
      }),
    )

    renderDetail()

    fireEvent.click(await screen.findByRole('button', { name: '예약 취소하기' }))
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    await waitFor(() => expect(body).toEqual({}))
  })

  it('RESERVATION_006은 취소 정책 불가로 안내한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(RESERVATION_DETAIL_PATH, () =>
        successResponse(reservationDetail()),
      ),
      http.post(RESERVATION_CANCEL_PATH, () =>
        errorResponse(
          409,
          ReservationErrorCode.CANCELLATION_NOT_ALLOWED,
          '취소할 수 없습니다.',
        ),
      ),
    )

    renderDetail()

    fireEvent.click(await screen.findByRole('button', { name: '예약 취소하기' }))
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    expect(
      await screen.findByText('매장의 취소 정책상 지금은 취소할 수 없습니다.'),
    ).toBeInTheDocument()
    // 취소 흐름에 생성 오류나 메뉴 재선택 안내를 섞지 않는다.
    expect(screen.queryByText(/메뉴 다시 선택/)).not.toBeInTheDocument()
  })

  it('RESERVATION_005는 상태 전이 불가로 안내한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(RESERVATION_DETAIL_PATH, () =>
        successResponse(reservationDetail()),
      ),
      http.post(RESERVATION_CANCEL_PATH, () =>
        errorResponse(
          409,
          ReservationErrorCode.INVALID_STATE,
          '현재 상태에서 취소할 수 없습니다.',
        ),
      ),
    )

    renderDetail()

    fireEvent.click(await screen.findByRole('button', { name: '예약 취소하기' }))
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    expect(
      await screen.findByText(
        '현재 상태에서는 취소할 수 없습니다. 최신 상태를 다시 확인해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('타인 소유와 부재를 나누어 노출하지 않는다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(RESERVATION_DETAIL_PATH, () =>
        errorResponse(
          404,
          ReservationErrorCode.NOT_FOUND,
          '예약을 찾을 수 없습니다.',
        ),
      ),
    )

    renderDetail()

    expect(await screen.findByText('예약을 찾을 수 없습니다.')).toBeInTheDocument()
    for (const label of ['권한', '다른 사용자', '타인']) {
      expect(screen.queryByText(new RegExp(label))).not.toBeInTheDocument()
    }
  })

  it('취소된 예약은 주체와 사유를 표시한다', async () => {
    respondWith(
      reservationDetail({
        status: 'CANCELLED',
        cancelledBy: 'STORE_OPERATOR',
        cancellationReason: '재료 소진',
      }),
    )

    renderDetail()

    expect(await screen.findByText('매장이 취소')).toBeInTheDocument()
    expect(screen.getByText('재료 소진')).toBeInTheDocument()
  })
})
