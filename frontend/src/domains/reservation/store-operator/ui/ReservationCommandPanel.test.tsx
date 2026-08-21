import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  operatorStorePath,
} from '../../../store/store-operator/test/handlers'
import { renderOperator } from '../../../store/store-operator/test/renderOperator'
import { StoreReservationDetailPage } from './StoreReservationDetailPage'

const RESERVATION_ID = '901'
const DETAIL_PATH = operatorStorePath(`/reservations/${RESERVATION_ID}`)
const CANCEL_PATH = `${DETAIL_PATH}/cancellations`
const FULFILL_PATH = `${DETAIL_PATH}/fulfillments`

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
    route: fillPath(STORE_OPERATOR_PATHS.reservation, {
      storeId: STORE_ID,
      reservationId: RESERVATION_ID,
    }),
    path: STORE_OPERATOR_PATHS.reservation,
  })
}

async function fillCancelReason(reason = '주방 설비 고장으로 운영이 어렵습니다.') {
  fireEvent.change(await screen.findByLabelText('취소 사유'), {
    target: { value: reason },
  })
}

describe('예약 처리', () => {
  it('사유 없이 취소하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(CANCEL_PATH, () => {
        called = true
        return successResponse(detail({ status: 'CANCELLED' }))
      }),
    )

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '예약 취소' }))

    expect(
      await screen.findByText('취소 사유를 입력해 주세요. 취소 이력에 남습니다.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('취소 사유와 멱등 키를 함께 보낸다', async () => {
    let body: Record<string, unknown> | null = null
    let idempotencyKey: string | null = null
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(CANCEL_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        idempotencyKey = request.headers.get('idempotency-key')
        return successResponse(
          detail({
            status: 'CANCELLED',
            cancelledBy: 'STORE_OPERATOR',
            cancellationReason: '주방 설비 고장으로 운영이 어렵습니다.',
          }),
        )
      }),
    )

    renderPage()
    await fillCancelReason()
    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }))

    await waitFor(() => expect(body).not.toBeNull())
    expect(body).toEqual({ reason: '주방 설비 고장으로 운영이 어렵습니다.' })
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i)
  })

  it('취소하면 상태와 취소 정보를 바로 반영한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(CANCEL_PATH, () =>
        successResponse(
          detail({
            status: 'CANCELLED',
            cancelledBy: 'STORE_OPERATOR',
            cancellationReason: '주방 설비 고장으로 운영이 어렵습니다.',
          }),
        ),
      ),
    )

    renderPage()
    await fillCancelReason()
    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }))

    expect(await screen.findByText('매장 취소')).toBeInTheDocument()
    expect(
      screen.getByText('이미 취소됨 상태입니다.'),
    ).toBeInTheDocument()
    // 처리한 예약에는 명령을 다시 열지 않는다.
    expect(
      screen.queryByRole('button', { name: '예약 취소' }),
    ).not.toBeInTheDocument()
  })

  it('방문 완료는 확인 단계를 거친다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(FULFILL_PATH, () => {
        called = true
        return successResponse(detail({ status: 'FULFILLED' }))
      }),
    )

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '방문 완료 처리' }))

    // 되돌릴 수 없는 명령이라 첫 클릭으로 실행하지 않는다.
    expect(called).toBe(false)
    expect(
      screen.getByRole('button', { name: '방문 완료로 처리' }),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '그만두기' }))
    expect(called).toBe(false)
    expect(
      screen.getByRole('button', { name: '방문 완료 처리' }),
    ).toBeInTheDocument()
  })

  it('방문 완료는 필드 없는 본문을 보낸다', async () => {
    let body: unknown = 'unset'
    let contentType: string | null = null
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(FULFILL_PATH, async ({ request }) => {
        contentType = request.headers.get('content-type')
        body = await request.json()
        return successResponse(detail({ status: 'FULFILLED' }))
      }),
    )

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '방문 완료 처리' }))
    fireEvent.click(screen.getByRole('button', { name: '방문 완료로 처리' }))

    await waitFor(() => expect(body).not.toBe('unset'))
    expect(body).toEqual({})
    // 본문을 생략하면 Content-Type이 빠져 서버가 415로 거절한다.
    expect(contentType).toContain('application/json')
  })

  it('방문 완료 후 상태를 반영하고 명령을 닫는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(FULFILL_PATH, () =>
        successResponse(detail({ status: 'FULFILLED' })),
      ),
    )

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '방문 완료 처리' }))
    fireEvent.click(screen.getByRole('button', { name: '방문 완료로 처리' }))

    expect(
      await screen.findByText('이미 방문 완료 상태입니다.'),
    ).toBeInTheDocument()
  })

  it('확정 상태가 아니면 처리 명령을 열지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () =>
        successResponse(detail({ status: 'FULFILLED' })),
      ),
    )

    renderPage()

    expect(
      await screen.findByText('이미 방문 완료 상태입니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '방문 완료 처리' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByLabelText('취소 사유')).not.toBeInTheDocument()
  })

  it('노쇼는 서버가 확정하는 상태라 처리 명령을 열지 않는다', async () => {
    // 상세 화면은 이미 확정된 NO_SHOW 결과에 처리 명령을 다시 열지 않는다.
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail({ status: 'NO_SHOW' }))),
    )

    renderPage()

    expect(await screen.findByText('이미 노쇼 상태입니다.')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '방문 완료 처리' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByLabelText('취소 사유')).not.toBeInTheDocument()
  })

  it('상태 전이 충돌은 목록을 다시 조회하도록 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(CANCEL_PATH, () =>
        errorResponse(409, 'RESERVATION_005', '현재 상태에서 처리할 수 없습니다.'),
      ),
    )

    renderPage()
    await fillCancelReason()
    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }))

    expect(
      await screen.findByText(
        '현재 예약 상태에서는 처리할 수 없습니다. 목록을 새로 조회해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('취소 정책 거절은 상태 충돌과 구분해 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(CANCEL_PATH, () =>
        errorResponse(409, 'RESERVATION_006', '정책상 취소할 수 없습니다.'),
      ),
    )

    renderPage()
    await fillCancelReason()
    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }))

    expect(
      await screen.findByText('지금은 취소 정책상 이 예약을 취소할 수 없습니다.'),
    ).toBeInTheDocument()
  })

  it('제한된 계정의 방문 완료 거절을 권한 문구로 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(FULFILL_PATH, () =>
        errorResponse(403, 'AUTH_011', '계정이 제한되었습니다.'),
      ),
    )

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '방문 완료 처리' }))
    fireEvent.click(screen.getByRole('button', { name: '방문 완료로 처리' }))

    expect(
      await screen.findByText(
        '현재 계정 상태로는 예약을 처리할 수 없습니다. 고객센터에 문의해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('같은 사유로 다시 시도하면 같은 멱등 키를 유지한다', async () => {
    const keys: string[] = []
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(CANCEL_PATH, ({ request }) => {
        keys.push(request.headers.get('idempotency-key') ?? '')
        return errorResponse(503, 'COMMON_012', '일시적으로 처리할 수 없습니다.')
      }),
    )

    renderPage()
    await fillCancelReason()
    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }))
    await waitFor(() => expect(keys).toHaveLength(1))

    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }))
    await waitFor(() => expect(keys).toHaveLength(2))

    expect(keys[0]).toBe(keys[1])
  })

  it('사유를 고쳐 보내면 새 멱등 키를 쓴다', async () => {
    const keys: string[] = []
    server.use(
      authenticatedOperator(),
      http.get(DETAIL_PATH, () => successResponse(detail())),
      http.post(CANCEL_PATH, ({ request }) => {
        keys.push(request.headers.get('idempotency-key') ?? '')
        return errorResponse(503, 'COMMON_012', '일시적으로 처리할 수 없습니다.')
      }),
    )

    renderPage()
    await fillCancelReason('설비 고장')
    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }))
    await waitFor(() => expect(keys).toHaveLength(1))

    await fillCancelReason('인력 사정으로 운영이 어렵습니다.')
    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }))
    await waitFor(() => expect(keys).toHaveLength(2))

    expect(keys[0]).not.toBe(keys[1])
  })
})
