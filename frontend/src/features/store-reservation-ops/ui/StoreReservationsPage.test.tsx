import { fireEvent, screen, waitFor, within } from '@testing-library/react'
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
import { StoreReservationsPage } from './StoreReservationsPage'

const RESERVATIONS_PATH = operatorStorePath('/reservations')

function summary(overrides: Record<string, unknown> = {}) {
  return {
    reservationId: '901',
    serviceDate: '2026-09-01',
    timeStatus: 'RESOLVED',
    startAt: '2026-09-01T09:30:00Z',
    serviceEndAt: '2026-09-01T11:00:00Z',
    timeZoneId: 'Asia/Seoul',
    totalPartySize: 4,
    status: 'CONFIRMED',
    ...overrides,
  }
}

function page(items: unknown[], overrides: Record<string, unknown> = {}) {
  return {
    items,
    page: {
      number: 0,
      size: 20,
      totalElements: items.length,
      totalPages: 1,
      hasNext: false,
      ...overrides,
    },
  }
}

function renderPage() {
  return renderOperator(<StoreReservationsPage />, {
    route: fillPath(ROUTES.storeOperatorReservations, { storeId: STORE_ID }),
    path: ROUTES.storeOperatorReservations,
  })
}

describe('매장 예약 목록 화면', () => {
  it('예약 시각을 매장 시간대 24시간 표기로 보여 준다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, () => successResponse(page([summary()]))),
    )

    renderPage()

    // 09:30Z는 Asia/Seoul에서 18:30이다.
    expect(await screen.findByText('18:30')).toBeInTheDocument()
    expect(screen.getByText('4명')).toBeInTheDocument()
    // 상태 필터의 선택지와 구분해 표 안의 뱃지를 확인한다.
    expect(
      within(screen.getByRole('table')).getByText('예약 확정'),
    ).toBeInTheDocument()
  })

  it('빈 조건은 query에 담지 않는다', async () => {
    const requests: string[] = []
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, ({ request }) => {
        requests.push(new URL(request.url).search)
        return successResponse(page([summary()]))
      }),
    )

    renderPage()
    await waitFor(() => expect(requests).toHaveLength(1))

    // 빈 문자열을 그대로 보내면 서버가 400으로 거절한다.
    expect(requests[0]).not.toContain('serviceDate=')
    expect(requests[0]).not.toContain('status=')
    expect(requests[0]).toContain('page=0')
  })

  it('날짜·상태 필터를 서버 query로 보낸다', async () => {
    const requests: string[] = []
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, ({ request }) => {
        requests.push(new URL(request.url).search)
        return successResponse(page([summary()]))
      }),
    )

    renderPage()
    await waitFor(() => expect(requests).toHaveLength(1))

    fireEvent.change(screen.getByLabelText('이용 날짜'), {
      target: { value: '2026-09-01' },
    })
    fireEvent.change(screen.getByLabelText('예약 상태'), {
      target: { value: 'CANCELLED' },
    })

    await waitFor(() => {
      const latest = requests[requests.length - 1]
      expect(latest).toContain('serviceDate=2026-09-01')
      expect(latest).toContain('status=CANCELLED')
    })
  })

  it('노쇼도 상태 필터로 조회할 수 있다', async () => {
    // 서버가 확정하는 상태지만 운영자는 결과를 찾아봐야 한다.
    const requests: string[] = []
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, ({ request }) => {
        requests.push(new URL(request.url).search)
        return successResponse(page([summary({ status: 'NO_SHOW' })]))
      }),
    )

    renderPage()
    await waitFor(() => expect(requests).toHaveLength(1))

    fireEvent.change(screen.getByLabelText('예약 상태'), {
      target: { value: 'NO_SHOW' },
    })

    await waitFor(() =>
      expect(requests[requests.length - 1]).toContain('status=NO_SHOW'),
    )

    // 같은 문구가 상태 선택 상자의 option에도 있다. 표의 행에서 찾는다.
    const row = (await screen.findByRole('rowheader', { name: '901' })).closest(
      'tr',
    )
    expect(row).not.toBeNull()
    expect(within(row as HTMLElement).getByText('노쇼')).toBeInTheDocument()
  })

  it('페이지를 넘기면 0 기반 page를 보낸다', async () => {
    const requests: string[] = []
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, ({ request }) => {
        requests.push(new URL(request.url).search)
        return successResponse(
          page([summary()], { totalElements: 40, totalPages: 2, hasNext: true }),
        )
      }),
    )

    renderPage()
    await screen.findByText('18:30')

    fireEvent.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() =>
      expect(requests[requests.length - 1]).toContain('page=1'),
    )
  })

  it('빈 결과를 오류와 구분해 표시한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, () => successResponse(page([]))),
    )

    renderPage()

    expect(
      await screen.findByText('조건에 맞는 예약이 없습니다.'),
    ).toBeInTheDocument()
  })

  it('조회 실패는 재시도 경로와 함께 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, () =>
        errorResponse(503, 'COMMON_012', '일시적으로 이용할 수 없습니다.'),
      ),
    )

    renderPage()

    expect(
      await screen.findByText(
        '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('권한 없는 매장은 다른 매장 정보를 보여 주지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, () =>
        errorResponse(403, 'STORE_003', '대상 매장의 대표 운영자가 아닙니다.'),
      ),
    )

    renderPage()

    expect(
      await screen.findByText('이 매장의 대표 운영자가 아닙니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('시각이 확정되지 않은 예약을 임의로 변환하지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, () =>
        successResponse(
          page([
            summary({
              timeStatus: 'LEGACY_UNRESOLVED',
              startAt: null,
              serviceEndAt: null,
              timeZoneId: null,
            }),
          ]),
        ),
      ),
    )

    renderPage()

    expect(await screen.findByText('시각 미확정')).toBeInTheDocument()
  })

  it('이번 범위에 없는 취소·방문 완료 버튼을 만들지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(RESERVATIONS_PATH, () => successResponse(page([summary()]))),
    )

    renderPage()
    await screen.findByText('18:30')

    // 계약은 있지만 이 Issue는 조회까지만 확정한다.
    expect(screen.queryByRole('button', { name: /취소/ })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /방문 완료/ }),
    ).not.toBeInTheDocument()
  })
})
