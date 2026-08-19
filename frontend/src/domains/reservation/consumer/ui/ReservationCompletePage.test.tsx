import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { PUBLIC_PATHS } from '../../../../app/routes/paths/publicPaths'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { ConsumerAuthProvider } from '../../../account/consumer/auth'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import { ReservationErrorCode } from '../model/errors'
import {
  MENU_ID,
  RESERVATION_DETAIL_PATH,
  RESERVATION_ID,
  reservationDetail,
} from '../test/fixtures'
import { ReservationCompletePage } from './ReservationCompletePage'

function renderComplete() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[`/reservations/${RESERVATION_ID}/complete`]}>
          <Routes>
            <Route
              path={CONSUMER_PATHS.reservationComplete}
              element={<ReservationCompletePage />}
            />
            <Route path={PUBLIC_PATHS.home} element={<p>홈 화면</p>} />
            <Route path={CONSUMER_PATHS.myReservations} element={<p>내 예약 목록</p>} />
            <Route path={CONSUMER_PATHS.reservationDetail} element={<p>예약 상세</p>} />
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

describe('예약 완료 화면', () => {
  it('축하 문구와 예약 요약을 보여 준다', async () => {
    respondWith(reservationDetail())

    renderComplete()

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: '예약이 완료되었습니다!',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText('파스타 마스터즈')).toBeInTheDocument()
    expect(
      screen.getByText('성인 2명 · 아동 0명 · 영유아 0명'),
    ).toBeInTheDocument()
  })

  it('서버가 준 식별자를 예약 번호로 그대로 보여 준다', async () => {
    respondWith(reservationDetail())

    renderComplete()

    await screen.findByRole('heading', { level: 1 })

    // 시안의 `#MY20231027-01` 같은 번호 체계는 계약에 없어 만들지 않는다.
    expect(screen.getByText('예약 번호')).toBeInTheDocument()
    expect(screen.getByText(RESERVATION_ID)).toBeInTheDocument()
  })

  it('미리 선택한 메뉴가 있으면 함께 보여 준다', async () => {
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

    renderComplete()

    expect(
      await screen.findByText('트러플 크림 파파델레 x 2'),
    ).toBeInTheDocument()
  })

  it('메뉴를 고르지 않았으면 메뉴 행을 만들지 않는다', async () => {
    respondWith(reservationDetail({ menuSelections: [] }))

    renderComplete()

    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByText('미리 선택한 메뉴')).not.toBeInTheDocument()
  })

  it('다음 행동으로 홈과 예약 내역을 제시한다', async () => {
    respondWith(reservationDetail())

    renderComplete()

    expect(
      await screen.findByRole('link', { name: '홈으로 이동' }),
    ).toHaveAttribute('href', PUBLIC_PATHS.home)
    expect(screen.getByRole('link', { name: '예약 내역 보기' })).toHaveAttribute(
      'href',
      CONSUMER_PATHS.myReservations,
    )
  })

  it('확정이 아닌 예약을 완료로 표시하지 않는다', async () => {
    respondWith(reservationDetail({ status: 'CANCELLED' }))

    renderComplete()

    expect(
      await screen.findByText(
        '이 예약은 확정 상태가 아닙니다. 예약 상세에서 현재 상태를 확인해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('예약이 완료되었습니다!'),
    ).not.toBeInTheDocument()
  })

  it('없는 예약은 찾을 수 없음으로 안내한다', async () => {
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

    renderComplete()

    expect(
      await screen.findByText('예약을 찾을 수 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('예약이 완료되었습니다!'),
    ).not.toBeInTheDocument()
  })
})
