import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { ConsumerAuthProvider } from '../../auth'
import { authenticatedConsumer } from '../../auth/test/handlers'
import {
  CONSUMER_ME_RESERVATIONS_PATH,
  reservationHistoryItem,
  reservationHistoryPage,
} from '../test/fixtures'
import { MyReservationsPage } from './MyReservationsPage'

let receivedSearch: URLSearchParams | null = null

function respondWith(
  ...items: ReturnType<typeof reservationHistoryItem>[]
) {
  server.use(
    authenticatedConsumer(),
    http.get(CONSUMER_ME_RESERVATIONS_PATH, ({ request }) => {
      receivedSearch = new URL(request.url).searchParams
      return successResponse(reservationHistoryPage(items))
    }),
  )
}

function renderList(route: string = ROUTES.myReservations) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route
              path={ROUTES.myReservations}
              element={<MyReservationsPage />}
            />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  receivedSearch = null
})

describe('내 예약 내역', () => {
  it('예약 목록을 표시한다', async () => {
    respondWith(reservationHistoryItem())

    renderList()

    expect(
      await screen.findByRole('link', { name: '파스타 마스터즈' }),
    ).toBeInTheDocument()

    // "예약 확정"은 필터 칩에도 있으므로 목록 항목 안으로 범위를 좁힌다.
    const item = within(screen.getByRole('listitem'))
    expect(item.getByText('예약 확정')).toBeInTheDocument()
    expect(item.getByText('2명')).toBeInTheDocument()
  })

  it('빈 결과는 오류가 아니라 안내로 표시한다', async () => {
    respondWith()

    renderList()

    expect(await screen.findByText('예약 내역이 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('상태 필터를 서버 query로 전달한다', async () => {
    respondWith(reservationHistoryItem())

    renderList()
    await screen.findByRole('link', { name: '파스타 마스터즈' })

    fireEvent.click(screen.getByRole('button', { name: '방문 완료' }))

    await waitFor(() => expect(receivedSearch?.get('status')).toBe('FULFILLED'))
  })

  it('전체 필터는 status를 보내지 않는다', async () => {
    respondWith(reservationHistoryItem())

    renderList(`${ROUTES.myReservations}?status=CANCELLED`)
    await screen.findByRole('link', { name: '파스타 마스터즈' })

    fireEvent.click(screen.getByRole('button', { name: '전체' }))

    await waitFor(() => expect(receivedSearch?.has('status')).toBe(false))
  })

  it('계약에 없는 상태값은 무시하고 전체로 조회한다', async () => {
    respondWith(reservationHistoryItem())

    renderList(`${ROUTES.myReservations}?status=NO_SHOW`)

    await screen.findByRole('link', { name: '파스타 마스터즈' })
    expect(receivedSearch?.has('status')).toBe(false)
  })

  it('페이지는 0 기반으로 보낸다', async () => {
    respondWith(reservationHistoryItem())

    renderList(`${ROUTES.myReservations}?page=2`)

    await screen.findByRole('link', { name: '파스타 마스터즈' })
    expect(receivedSearch?.get('page')).toBe('2')
  })

  it('시각을 매장 시간대 기준으로 표시한다', async () => {
    respondWith(reservationHistoryItem())

    renderList()

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    // 2026-09-01T19:00+09:00 → 매장 시간대(Asia/Seoul) 기준 19:00
    expect(screen.getByText(/19:00/)).toBeInTheDocument()
  })

  it('사용자 기기 시간대가 아니라 매장 시간대로 표시한다', async () => {
    // 같은 순간이지만 매장 시간대가 다르면 다른 현지 시각이어야 한다.
    respondWith(
      reservationHistoryItem({
        startAt: '2026-09-01T19:00:00+09:00',
        timeZoneId: 'UTC',
      }),
    )

    renderList()

    await screen.findByRole('link', { name: '파스타 마스터즈' })
    expect(screen.getByText(/10:00/)).toBeInTheDocument()
  })

  it('LEGACY_UNRESOLVED는 시각을 지어내지 않는다', async () => {
    respondWith(
      reservationHistoryItem({
        timeStatus: 'LEGACY_UNRESOLVED',
        startAt: null,
        serviceEndAt: null,
        timeZoneId: null,
      }),
    )

    renderList()

    expect(
      await screen.findByText('2026-09-01 (시각 정보 없음)'),
    ).toBeInTheDocument()
  })

  it('결제·노쇼·체크인 항목을 만들지 않는다', async () => {
    respondWith(reservationHistoryItem())

    renderList()
    await screen.findByRole('link', { name: '파스타 마스터즈' })

    for (const label of ['결제', '노쇼', '체크인', '웨이팅']) {
      expect(screen.queryByText(new RegExp(label))).not.toBeInTheDocument()
    }
  })

  it('조회 실패는 오류 상태로 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_RESERVATIONS_PATH, () =>
        errorResponse(400, 'COMMON_001', '검증에 실패했습니다.'),
      ),
    )

    renderList()

    expect(
      await screen.findByText('입력한 내용을 다시 확인해 주세요.'),
    ).toBeInTheDocument()
  })
})
