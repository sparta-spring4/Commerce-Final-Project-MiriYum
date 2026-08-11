import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { ConsumerAuthProvider } from '../../auth'
import { authenticatedConsumer } from '../../auth/test/handlers'
import { PickupErrorCode, type PickupReservation } from '../model/pickup'
import { PickupCreatePage } from './PickupCreatePage'
import { PickupDetailPage } from './PickupDetailPage'

const STORE_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1XA'
const MENU_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1MA'
const PICKUP_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1P1'

const AVAILABILITY_PATH = '/api/v1/stores/:storeId/pickup-availability'
const CREATE_PATH = '/api/v1/consumers/pickup-reservations'
const DETAIL_PATH =
  '/api/v1/consumers/pickup-reservations/:pickupReservationId'
const CANCEL_PATH =
  '/api/v1/consumers/pickup-reservations/:pickupReservationId/cancellations'

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
            <Route path={ROUTES.pickupCreate} element={<PickupCreatePage />} />
            <Route path={ROUTES.pickupDetail} element={<PickupDetailPage />} />
            <Route path={ROUTES.stores} element={<LocationProbe />} />
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

    expect(
      await screen.findByText('트러플 크림 파파델레 x 2 · 64,000원'),
    ).toBeInTheDocument()
    expect(screen.getByText('합계 64,000원')).toBeInTheDocument()
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
