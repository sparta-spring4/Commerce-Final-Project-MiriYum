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

    // 시안은 메뉴와 금액을 한 줄의 양 끝에 놓는다. 두 값은 각각 표시된다.
    expect(
      await screen.findByText('트러플 크림 파파델레 x 2'),
    ).toBeInTheDocument()
    expect(screen.getByText('64,000원')).toBeInTheDocument()
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

  /*
   * 결과 불명 회귀.
   *
   * 취소가 서버에 반영된 뒤 응답만 유실됐을 수 있다. 새 키로 다시 보내면
   * 두 번째 취소 명령이 되어 이미 취소된 예약을 다시 취소하려 든다.
   */
  it('결과를 알 수 없는 실패 뒤 재시도는 같은 멱등 키를 보낸다', async () => {
    const keys: string[] = []
    let attempt = 0

    server.use(
      authenticatedConsumer(),
      http.get(RESERVATION_DETAIL_PATH, () =>
        successResponse(reservationDetail()),
      ),
      http.post(RESERVATION_CANCEL_PATH, ({ request }) => {
        attempt += 1
        const key = request.headers.get('Idempotency-Key')
        if (key !== null) {
          keys.push(key)
        }
        if (attempt === 1) {
          return errorResponse(500, 'COMMON_011', '서버 오류입니다.')
        }
        return successResponse(reservationDetail({ status: 'CANCELLED' }))
      }),
    )

    renderDetail()

    fireEvent.click(await screen.findByRole('button', { name: '예약 취소하기' }))
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    expect(
      await screen.findByText(
        '취소 처리 여부를 확인하지 못했습니다. 다시 시도하지 말고 최신 상태를 확인해 주세요.',
      ),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    await waitFor(() => expect(attempt).toBe(2))
    expect(new Set(keys).size).toBe(1)
  })

  /*
   * 취소 사유는 요청 본문이라 `request_fingerprint`의 일부다. 사유가 바뀌면
   * 같은 키를 재사용할 수 없고 서버가 `COMMON_007`로 거절한다.
   */
  it('확정 실패 뒤 사유를 고치면 새 멱등 키로 보낸다', async () => {
    const keys: string[] = []
    let attempt = 0

    server.use(
      authenticatedConsumer(),
      http.get(RESERVATION_DETAIL_PATH, () =>
        successResponse(reservationDetail()),
      ),
      http.post(RESERVATION_CANCEL_PATH, ({ request }) => {
        attempt += 1
        const key = request.headers.get('Idempotency-Key')
        if (key !== null) {
          keys.push(key)
        }
        if (attempt === 1) {
          // 서버가 거절을 확정했다. 명령은 반영되지 않았다.
          return errorResponse(
            409,
            ReservationErrorCode.CANCELLATION_NOT_ALLOWED,
            '취소할 수 없습니다.',
          )
        }
        return successResponse(reservationDetail({ status: 'CANCELLED' }))
      }),
    )

    renderDetail()

    fireEvent.click(await screen.findByRole('button', { name: '예약 취소하기' }))
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))
    await waitFor(() => expect(attempt).toBe(1))

    fireEvent.change(screen.getByLabelText('취소 사유 (선택)'), {
      target: { value: '사유를 고쳤습니다.' },
    })
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    await waitFor(() => expect(attempt).toBe(2))
    expect(new Set(keys).size).toBe(2)
  })

  /*
   * 결과 불명 뒤에는 사유를 바꾼 재전송을 막는다.
   *
   * 앞선 명령이 이미 반영됐을 수 있는데 지문이 달라 같은 키를 쓸 수 없고,
   * 새 키를 발급하면 취소가 두 번 나간다. 어느 쪽도 안전하지 않으므로
   * 보내지 않고 상태 확인으로 보낸다.
   */
  it('결과 불명 뒤 사유를 고친 재전송은 보내지 않고 상태 확인으로 안내한다', async () => {
    let attempt = 0

    server.use(
      authenticatedConsumer(),
      http.get(RESERVATION_DETAIL_PATH, () =>
        successResponse(reservationDetail()),
      ),
      http.post(RESERVATION_CANCEL_PATH, () => {
        attempt += 1
        return errorResponse(500, 'COMMON_011', '서버 오류입니다.')
      }),
    )

    renderDetail()

    fireEvent.click(await screen.findByRole('button', { name: '예약 취소하기' }))
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))
    await waitFor(() => expect(attempt).toBe(1))

    fireEvent.change(screen.getByLabelText('취소 사유 (선택)'), {
      target: { value: '사유를 고쳤습니다.' },
    })
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    expect(
      await screen.findByText(/사유를 바꿔 다시 보내면 취소가 두 번 처리될 수 있습니다/),
    ).toBeInTheDocument()
    // 두 번째 요청은 나가지 않는다.
    expect(attempt).toBe(1)
  })

  it('같은 사유로 다시 누르면 같은 멱등 키를 보낸다', async () => {
    const keys: string[] = []
    let attempt = 0

    server.use(
      authenticatedConsumer(),
      http.get(RESERVATION_DETAIL_PATH, () =>
        successResponse(reservationDetail()),
      ),
      http.post(RESERVATION_CANCEL_PATH, ({ request }) => {
        attempt += 1
        const key = request.headers.get('Idempotency-Key')
        if (key !== null) {
          keys.push(key)
        }
        if (attempt === 1) {
          return errorResponse(500, 'COMMON_011', '서버 오류입니다.')
        }
        return successResponse(reservationDetail({ status: 'CANCELLED' }))
      }),
    )

    renderDetail()

    fireEvent.click(await screen.findByRole('button', { name: '예약 취소하기' }))
    fireEvent.change(screen.getByLabelText('취소 사유 (선택)'), {
      target: { value: '일정이 바뀌었습니다.' },
    })
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))
    await waitFor(() => expect(attempt).toBe(1))

    // 사유를 그대로 두고 다시 누른다. 요청 지문이 같으므로 같은 키를 유지해야
    // 서버가 앞선 결과로 수렴시킨다.
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    await waitFor(() => expect(attempt).toBe(2))
    expect(new Set(keys).size).toBe(1)
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
