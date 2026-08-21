import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  operatorStorePath,
} from '../../../store/store-operator/test/handlers'
import { renderOperator } from '../../../store/store-operator/test/renderOperator'
import { PickupReservationDetailPage } from './PickupReservationDetailPage'
import { PickupReservationsPage } from './PickupReservationsPage'

const PICKUPS_PATH = operatorStorePath('/pickup-reservations')

const pickup = {
  pickupReservationId: '91',
  storeId: STORE_ID,
  storeName: '카페 에비뉴',
  pickupDate: '2026-08-21',
  pickupTime: '12:30:00',
  status: 'CONFIRMED' as const,
  items: [{ menuId: '11', menuName: '아메리카노', unitPrice: 4500, quantity: 2 }],
  cancelledBy: null,
  cancellationReason: null,
  createdAt: '2026-08-20T10:00:00+09:00',
}

describe('점주 픽업 운영 화면', () => {
  it('날짜·상태 조건으로 목록을 조회하고 상세로 이동한다', async () => {
    let received = ''
    server.use(
      authenticatedOperator(),
      http.get(PICKUPS_PATH, ({ request }) => {
        received = new URL(request.url).search
        return successResponse({
          items: [pickup],
          page: { number: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
        })
      }),
    )

    renderOperator(<PickupReservationsPage />, {
      route: fillPath(STORE_OPERATOR_PATHS.pickupReservations, { storeId: STORE_ID }),
      path: STORE_OPERATOR_PATHS.pickupReservations,
      probePaths: [STORE_OPERATOR_PATHS.pickupReservation],
    })

    expect(await screen.findByText('아메리카노 × 2')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('픽업 날짜'), {
      target: { value: '2026-08-21' },
    })
    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'CONFIRMED' },
    })

    await waitFor(() => {
      expect(received).toContain('pickupDate=2026-08-21')
      expect(received).toContain('status=CONFIRMED')
    })

    fireEvent.click(screen.getByRole('link', { name: '픽업 91 상세' }))
    expect(screen.getByTestId('location')).toHaveTextContent(
      '/store-operator/stores/7/pickup-reservations/91',
    )
  })

  it('확인 후 수령 완료하고 서버 응답 상태를 표시한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(`${PICKUPS_PATH}/91`, () => successResponse(pickup)),
      http.post(`${PICKUPS_PATH}/91/fulfillments`, async ({ request }) => {
        expect(request.headers.get('Idempotency-Key')).toMatch(/[0-9a-f-]{36}/)
        expect(await request.json()).toEqual({})
        return successResponse({ ...pickup, status: 'PICKED_UP' })
      }),
    )

    renderOperator(<PickupReservationDetailPage />, {
      route: fillPath(STORE_OPERATOR_PATHS.pickupReservation, {
        storeId: STORE_ID,
        pickupReservationId: '91',
      }),
      path: STORE_OPERATOR_PATHS.pickupReservation,
    })

    expect(await screen.findByText('아메리카노')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '수령 완료 처리' }))
    fireEvent.click(screen.getByRole('button', { name: '수령 완료로 확정' }))

    expect(await screen.findByText('수령 완료')).toBeInTheDocument()
  })

  it('매장 취소 사유를 필수로 보내고 충돌을 성공으로 표시하지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(`${PICKUPS_PATH}/91`, () => successResponse(pickup)),
      http.post(`${PICKUPS_PATH}/91/cancellations`, async ({ request }) => {
        expect(await request.json()).toEqual({ reason: '재료 소진' })
        return HttpResponse.json(
          { code: 'PICKUP_005', message: '현재 상태에서는 취소할 수 없습니다.' },
          { status: 409 },
        )
      }),
    )

    renderOperator(<PickupReservationDetailPage />, {
      route: fillPath(STORE_OPERATOR_PATHS.pickupReservation, {
        storeId: STORE_ID,
        pickupReservationId: '91',
      }),
      path: STORE_OPERATOR_PATHS.pickupReservation,
    })

    await screen.findByText('아메리카노')
    fireEvent.change(screen.getByLabelText('취소 사유'), {
      target: { value: '재료 소진' },
    })
    fireEvent.click(screen.getByRole('button', { name: '픽업 예약 취소' }))

    expect(
      await screen.findByText('현재 픽업 상태에서는 처리할 수 없습니다. 최신 상태를 확인해 주세요.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('취소 완료')).not.toBeInTheDocument()
  })
})
