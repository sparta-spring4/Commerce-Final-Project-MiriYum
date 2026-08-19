import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { PUBLIC_PATHS } from '../../../../app/routes/paths/publicPaths'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { ConsumerAuthProvider } from '../../../account/consumer/auth'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import { PickupErrorCode, type PickupReservation } from '../model/pickup'
import { PickupCreatePage } from './PickupCreatePage'
import { PickupDetailPage } from './PickupDetailPage'

const STORE_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1XA'
const MENU_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1MA'
const PICKUP_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1P1'

const AVAILABILITY_PATH = '/api/v1/stores/:storeId/pickup-availability'
const CREATE_PATH = '/api/v1/consumers/me/pickup-reservations'
const DETAIL_PATH =
  '/api/v1/consumers/me/pickup-reservations/:pickupReservationId'
const CANCEL_PATH =
  '/api/v1/consumers/me/pickup-reservations/:pickupReservationId/cancellations'

function availability(availableQuantity = 5) {
  return {
    storeId: STORE_ID,
    pickupDate: '2026-09-01',
    slots: [
      {
        pickupTime: '18:30',
        menus: [
          {
            menuId: MENU_ID,
            menuName: '트러플 크림 파파델레',
            unitPrice: 32000,
            serviceDate: '2026-09-01',
            startTime: '18:30',
            endTime: '19:00',
            availabilityStatus: 'AVAILABLE' as const,
            availableQuantity,
          },
        ],
      },
      { pickupTime: '19:00', menus: [] },
    ],
  }
}

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

function LocationProbe() {
  const { pathname } = useLocation()
  return <p data-testid="location">{pathname}</p>
}

function renderFlow(entry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route path={CONSUMER_PATHS.pickupCreate} element={<PickupCreatePage />} />
            <Route path={CONSUMER_PATHS.pickupDetail} element={<PickupDetailPage />} />
            <Route path={PUBLIC_PATHS.stores} element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </QueryClientProvider>,
  )
}

let createBody: Record<string, unknown> | null = null

beforeEach(() => {
  createBody = null
})

describe('픽업 예약 작성', () => {
  it('시간대와 메뉴를 골라 생성한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () => successResponse(availability())),
      http.post(CREATE_PATH, async ({ request }) => {
        createBody = (await request.json()) as Record<string, unknown>
        return successResponse(pickupReservation())
      }),
    )

    renderFlow(`/stores/${STORE_ID}/pickup?pickupDate=2026-09-01`)

    fireEvent.click(await screen.findByRole('button', { name: '18:30' }))
    fireEvent.click(
      await screen.findByRole('button', {
        name: '트러플 크림 파파델레 수량 늘리기',
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: '픽업 예약하기' }))

    await waitFor(() => expect(createBody).not.toBeNull())

    // endTime·partySize를 보내지 않는다.
    expect(Object.keys(createBody as object).sort()).toEqual([
      'menuSelections',
      'pickupDate',
      'pickupTime',
      'storeId',
    ])
    expect(createBody?.menuSelections).toEqual([{ menuId: MENU_ID, quantity: 1 }])
  })

  /*
   * 결과 불명 픽업을 확인할 경로.
   *
   * 1차 MVP에 픽업 목록 화면이 없고 응답이 유실되면 `pickupReservationId`도
   * 모른다. "내 예약에서 확인"은 통하지 않는다. 대신 같은 키로 한 번 더 보내면
   * 계약이 저장된 최초 결과를 재생하므로, 그 재전송을 사용자에게 준다.
   */
  it('결과를 알 수 없으면 같은 키로 결과를 확인하는 경로를 준다', async () => {
    const keys: string[] = []
    let attempt = 0

    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () => successResponse(availability())),
      http.post(CREATE_PATH, ({ request }) => {
        attempt += 1
        const key = request.headers.get('Idempotency-Key')
        if (key !== null) {
          keys.push(key)
        }
        if (attempt === 1) {
          return errorResponse(500, 'COMMON_011', '서버 오류입니다.')
        }
        // 계약상 같은 키·같은 지문의 재전송은 저장된 최초 결과를 재생한다.
        return successResponse(pickupReservation())
      }),
    )

    renderFlow(`/stores/${STORE_ID}/pickup?pickupDate=2026-09-01`)

    fireEvent.click(await screen.findByRole('button', { name: '18:30' }))
    fireEvent.click(
      await screen.findByRole('button', {
        name: '트러플 크림 파파델레 수량 늘리기',
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: '픽업 예약하기' }))

    // 재시도가 아니라 결과 확인으로 안내한다.
    expect(
      await screen.findByText(/예약이 두 건 잡히지 않습니다/),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '예약 결과 확인' }))

    await waitFor(() => expect(attempt).toBe(2))
    // 같은 키여야 서버가 저장된 결과를 재생한다.
    expect(new Set(keys).size).toBe(1)
  })

  /*
   * 결과 불명 뒤 조건을 바꿔 다시 보내면 지문도 키도 달라져 두 번째 픽업이
   * 생긴다. 첫 요청이 이미 커밋된 채 응답만 유실됐을 수 있으므로, 결과를
   * 확정하기 전에는 조건을 바꾼 전송을 내보내지 않는다.
   */
  it('결과 불명 뒤 조건을 바꿔 제출해도 두 번째 요청이 나가지 않는다', async () => {
    let attempt = 0

    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () => successResponse(availability())),
      http.post(CREATE_PATH, () => {
        attempt += 1
        return errorResponse(500, 'COMMON_011', '서버 오류입니다.')
      }),
    )

    renderFlow(`/stores/${STORE_ID}/pickup?pickupDate=2026-09-01`)

    fireEvent.click(await screen.findByRole('button', { name: '18:30' }))
    const increase = await screen.findByRole('button', {
      name: '트러플 크림 파파델레 수량 늘리기',
    })
    fireEvent.click(increase)
    fireEvent.click(screen.getByRole('button', { name: '픽업 예약하기' }))

    await waitFor(() => expect(attempt).toBe(1))

    // 결과가 확정되기 전에는 조건을 바꿀 수 없다.
    await waitFor(() => expect(increase).toBeDisabled())
    expect(screen.getByRole('button', { name: '픽업 예약하기' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '18:30' })).toBeDisabled()

    // 뒤로가기 등으로 URL이 바뀌어 조건이 달라져도 전송을 막는다.
    fireEvent.click(screen.getByRole('button', { name: '예약 결과 확인' }))
    await waitFor(() => expect(attempt).toBe(2))
  })

  it('메뉴를 고르지 않으면 제출하지 않는다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () => successResponse(availability())),
      http.post(CREATE_PATH, async ({ request }) => {
        createBody = (await request.json()) as Record<string, unknown>
        return successResponse(pickupReservation())
      }),
    )

    renderFlow(`/stores/${STORE_ID}/pickup?pickupDate=2026-09-01`)

    fireEvent.click(await screen.findByRole('button', { name: '18:30' }))
    fireEvent.click(screen.getByRole('button', { name: '픽업 예약하기' }))

    expect(
      await screen.findByText('픽업할 메뉴를 한 가지 이상 선택해 주세요.'),
    ).toBeInTheDocument()
    expect(createBody).toBeNull()
  })

  it('시간대를 바꾸면 이전 구간의 메뉴 선택을 버린다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () => successResponse(availability())),
    )

    renderFlow(`/stores/${STORE_ID}/pickup?pickupDate=2026-09-01`)

    fireEvent.click(await screen.findByRole('button', { name: '18:30' }))
    fireEvent.click(
      await screen.findByRole('button', {
        name: '트러플 크림 파파델레 수량 늘리기',
      }),
    )
    await waitFor(() =>
      expect(
        screen.getByLabelText('트러플 크림 파파델레 선택 수량'),
      ).toHaveTextContent('1'),
    )

    fireEvent.click(screen.getByRole('button', { name: '19:00' }))
    fireEvent.click(screen.getByRole('button', { name: '18:30' }))

    await waitFor(() =>
      expect(
        screen.getByLabelText('트러플 크림 파파델레 선택 수량'),
      ).toHaveTextContent('0'),
    )
  })

  it('서버 잔여를 넘겨 수량을 고를 수 없다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () => successResponse(availability(1))),
    )

    renderFlow(`/stores/${STORE_ID}/pickup?pickupDate=2026-09-01`)

    fireEvent.click(await screen.findByRole('button', { name: '18:30' }))
    const increase = await screen.findByRole('button', {
      name: '트러플 크림 파파델레 수량 늘리기',
    })
    fireEvent.click(increase)

    await waitFor(() => expect(increase).toBeDisabled())
  })

  it('PICKUP_003 구간 오류를 서버 code로 안내한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () => successResponse(availability())),
      http.post(CREATE_PATH, () =>
        errorResponse(409, PickupErrorCode.SLOT_INVALID, '구간이 없습니다.'),
      ),
    )

    renderFlow(`/stores/${STORE_ID}/pickup?pickupDate=2026-09-01`)

    fireEvent.click(await screen.findByRole('button', { name: '18:30' }))
    fireEvent.click(
      await screen.findByRole('button', {
        name: '트러플 크림 파파델레 수량 늘리기',
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: '픽업 예약하기' }))

    expect(
      await screen.findByText(
        '선택한 픽업 시간대를 사용할 수 없습니다. 시간대를 다시 골라 주세요.',
      ),
    ).toBeInTheDocument()
  })
})

describe('픽업 예약 상세', () => {
  it('주문 내역과 합계를 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(DETAIL_PATH, () => successResponse(pickupReservation())),
    )

    renderFlow(`/pickup-reservations/${PICKUP_ID}`)

    // 시안은 메뉴와 금액을 한 줄의 양 끝에 놓는다. 두 값은 각각 표시된다.
    expect(
      await screen.findByText('트러플 크림 파파델레 x 2'),
    ).toBeInTheDocument()
    expect(screen.getAllByText('64,000원')).toHaveLength(2)
    expect(screen.getByText('합계')).toBeInTheDocument()
  })

  it('픽업 완료 상태에는 취소 버튼을 두지 않는다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(DETAIL_PATH, () =>
        successResponse(pickupReservation({ status: 'PICKED_UP' })),
      ),
    )

    renderFlow(`/pickup-reservations/${PICKUP_ID}`)

    expect(await screen.findByText('픽업 완료')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '픽업 예약 취소하기' }),
    ).not.toBeInTheDocument()
  })

  it('취소는 Idempotency-Key를 붙여 보낸다', async () => {
    let idempotencyKey: string | null = null

    server.use(
      authenticatedConsumer(),
      http.get(DETAIL_PATH, () => successResponse(pickupReservation())),
      http.post(CANCEL_PATH, ({ request }) => {
        idempotencyKey = request.headers.get('Idempotency-Key')
        return successResponse(pickupReservation({ status: 'CANCELLED' }))
      }),
    )

    renderFlow(`/pickup-reservations/${PICKUP_ID}`)

    fireEvent.click(
      await screen.findByRole('button', { name: '픽업 예약 취소하기' }),
    )
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    await waitFor(() => expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/))
  })

  it('PICKUP_006은 취소 정책 불가로 안내한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(DETAIL_PATH, () => successResponse(pickupReservation())),
      http.post(CANCEL_PATH, () =>
        errorResponse(
          409,
          PickupErrorCode.CANCELLATION_NOT_ALLOWED,
          '취소할 수 없습니다.',
        ),
      ),
    )

    renderFlow(`/pickup-reservations/${PICKUP_ID}`)

    fireEvent.click(
      await screen.findByRole('button', { name: '픽업 예약 취소하기' }),
    )
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }))

    expect(
      await screen.findByText('매장의 취소 정책상 지금은 취소할 수 없습니다.'),
    ).toBeInTheDocument()
  })

  it('픽업 목록 화면으로 가는 경로를 만들지 않는다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(DETAIL_PATH, () => successResponse(pickupReservation())),
    )

    renderFlow(`/pickup-reservations/${PICKUP_ID}`)

    await screen.findByRole('heading', { level: 1 })
    // 일반 사용자 픽업 목록 계약이 없다.
    expect(
      screen.queryByRole('link', { name: /픽업 내역|내 픽업/ }),
    ).not.toBeInTheDocument()
  })
})
