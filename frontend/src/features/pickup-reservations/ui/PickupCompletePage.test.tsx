import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { ConsumerAuthProvider } from '../../auth'
import { authenticatedConsumer } from '../../auth/test/handlers'
import type { PickupReservation } from '../model/pickup'
import { PickupCompletePage } from './PickupCompletePage'

const STORE_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1XA'
const MENU_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1MA'
const PICKUP_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1P1'
const DETAIL_PATH =
  '/api/v1/consumers/me/pickup-reservations/:pickupReservationId'

function pickupReservation(
  overrides: Partial<PickupReservation> = {},
): PickupReservation {
  return {
    pickupReservationId: PICKUP_ID,
    storeId: STORE_ID,
    storeName: '파스타 마스터즈',
    pickupDate: '2026-09-01',
    pickupTime: '18:30',
    status: 'CONFIRMED',
    items: [
      {
        menuId: MENU_ID,
        menuName: '트러플 크림 파파델레',
        unitPrice: 32000,
        quantity: 2,
      },
    ],
    cancelledBy: null,
    cancellationReason: null,
    createdAt: '2026-08-11T10:00:00+09:00',
    ...overrides,
  }
}

function renderComplete() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter
          initialEntries={[`/pickup-reservations/${PICKUP_ID}/complete`]}
        >
          <Routes>
            <Route
              path={ROUTES.pickupComplete}
              element={<PickupCompletePage />}
            />
            <Route path={ROUTES.home} element={<p>홈 화면</p>} />
            <Route path={ROUTES.pickupDetail} element={<p>픽업 상세</p>} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </QueryClientProvider>,
  )
}

describe('픽업 예약 완료 화면', () => {
  it('축하 문구와 주문 요약을 보여 준다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(DETAIL_PATH, () => successResponse(pickupReservation())),
    )

    renderComplete()

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: '픽업 예약이 완료되었습니다!',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText('2026-09-01 18:30')).toBeInTheDocument()
    expect(screen.getByText('트러플 크림 파파델레 x 2')).toBeInTheDocument()
    expect(screen.getByText('64,000원')).toBeInTheDocument()
  })

  it('픽업 목록이 아니라 픽업 상세로 보낸다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(DETAIL_PATH, () => successResponse(pickupReservation())),
    )

    renderComplete()

    // 일반 사용자 픽업 목록 계약이 없다.
    expect(
      await screen.findByRole('link', { name: '픽업 예약 보기' }),
    ).toHaveAttribute('href', `/pickup-reservations/${PICKUP_ID}`)
    expect(
      screen.queryByRole('link', { name: /픽업 내역/ }),
    ).not.toBeInTheDocument()
  })

  it('확정이 아닌 픽업 예약을 완료로 표시하지 않는다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(DETAIL_PATH, () =>
        successResponse(pickupReservation({ status: 'CANCELLED' })),
      ),
    )

    renderComplete()

    expect(
      await screen.findByText(
        '이 픽업 예약은 확정 상태가 아닙니다. 상세에서 현재 상태를 확인해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('픽업 예약이 완료되었습니다!'),
    ).not.toBeInTheDocument()
  })
})
