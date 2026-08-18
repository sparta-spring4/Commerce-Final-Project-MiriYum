import { screen } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { ROUTES, fillPath } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  operatorStorePath,
} from '../../store-operator/test/handlers'
import { renderOperator } from '../../store-operator/test/renderOperator'
import { StoreReservationDetailPage } from './StoreReservationDetailPage'

const RESERVATION_ID = '901'
const DETAIL_PATH = operatorStorePath(`/reservations/${RESERVATION_ID}`)

function detail(overrides: Record<string, unknown> = {}) {
  return {
    reservationId: RESERVATION_ID,
    storeId: STORE_ID,
    storeName: '카페 에비뉴',
    serviceDate: '2026-09-01',
    timeStatus: 'RESOLVED',
    startAt: '2026-09-01T09:30:00Z',
    serviceEndAt: '2026-09-01T11:00:00Z',
    timeZoneId: 'Asia/Seoul',
    party: { adultCount: 2, childCount: 1, infantCount: 1, totalCount: 4 },
    status: 'CONFIRMED',
    menuSelections: [],
    cancelledBy: null,
    cancellationReason: null,
    createdAt: '2026-08-20T02:00:00Z',
    ...overrides,
  }
}

function renderPage() {
  return renderOperator(<StoreReservationDetailPage />, {
    route: fillPath(ROUTES.storeOperatorReservation, {
      storeId: STORE_ID,
      reservationId: RESERVATION_ID,
    }),
    path: ROUTES.storeOperatorReservation,
  })
}

describe('매장 예약 상세 화면', () => {
  it('확정 내용을 매장 시간대 기준으로 보여 준다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
    )

    renderPage()

    expect(await screen.findByText('2026-09-01 18:30')).toBeInTheDocument()
    expect(
      screen.getByText('성인 2 · 아동 1 · 영유아 1 (합계 4명)'),
    ).toBeInTheDocument()
  })

  it('선택한 메뉴 스냅샷을 표로 보여 준다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () =>
        successResponse(
          detail({
            menuSelections: [
              {
                menuId: '11',
                menuName: '에스프레소',
                unitPrice: 4500,
                quantity: 2,
              },
            ],
          }),
        ),
      ),
    )

    renderPage()

    expect(await screen.findByText('에스프레소')).toBeInTheDocument()
    expect(screen.getByText('4,500원')).toBeInTheDocument()
  })

  it('취소된 예약은 주체와 사유를 함께 보여 준다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () =>
        successResponse(
          detail({
            status: 'CANCELLED',
            cancelledBy: 'STORE_OPERATOR',
            cancellationReason: '설비 고장',
          }),
        ),
      ),
    )

    renderPage()

    expect(await screen.findByText('매장 취소')).toBeInTheDocument()
    expect(screen.getByText('설비 고장')).toBeInTheDocument()
  })

  it('시각이 확정되지 않은 예약을 임의로 변환하지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () =>
        successResponse(
          detail({
            timeStatus: 'LEGACY_UNRESOLVED',
            startAt: null,
            serviceEndAt: null,
            timeZoneId: null,
          }),
        ),
      ),
    )

    renderPage()

    expect(await screen.findAllByText('확정되지 않음')).toHaveLength(2)
    expect(
      screen.getByText(/화면에서 임의로 변환하지 않습니다/),
    ).toBeInTheDocument()
  })

  it('없는 예약은 재시도를 권하지 않고 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () =>
        errorResponse(404, 'RESERVATION_001', '예약을 찾을 수 없습니다.'),
      ),
    )

    renderPage()

    expect(await screen.findByText('예약을 찾을 수 없습니다.')).toBeInTheDocument()
  })

  it('확정 예약에는 취소·방문 완료 처리를 함께 연다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
    )

    renderPage()
    await screen.findByText('2026-09-01 18:30')

    expect(
      screen.getByRole('button', { name: '방문 완료 처리' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '예약 취소' })).toBeInTheDocument()
  })
})
