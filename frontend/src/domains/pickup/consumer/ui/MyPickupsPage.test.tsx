import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ConsumerAuthProvider } from '../../../account/consumer/auth'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { MyPickupsPage } from './MyPickupsPage'

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter><MyPickupsPage /></MemoryRouter>
      </ConsumerAuthProvider>
    </QueryClientProvider>,
  )
}

describe('내 픽업 내역', () => {
  it('픽업 상태와 메뉴 수량을 상세 링크와 함께 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get('/api/v1/consumers/me/pickup-reservations', ({ request }) => {
        expect(new URL(request.url).searchParams.get('page')).toBe('0')
        return successResponse({
          items: [{
            pickupReservationId: 'pickup-77',
            storeId: 'store-22',
            storeName: '미리윰 강남점',
            pickupDate: '2026-09-01',
            pickupTime: '18:30',
            status: 'CONFIRMED',
            items: [{ menuId: 'menu-33', menuName: '바질 파스타', unitPrice: 12000, quantity: 2 }],
            cancelledBy: null,
            cancellationReason: null,
            createdAt: '2026-08-20T10:00:00+09:00',
          }],
          page: { number: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
        })
      }),
    )

    renderPage()

    expect(await screen.findByText('미리윰 강남점')).toBeInTheDocument()
    expect(screen.getByText('픽업 확정')).toBeInTheDocument()
    expect(screen.getByText('바질 파스타 외 0개 · 총 2개')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '픽업 상세 보기' })).toHaveAttribute(
      'href', '/pickup-reservations/pickup-77',
    )
  })

  it('내역이 없으면 정상 빈 상태를 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get('/api/v1/consumers/me/pickup-reservations', () => successResponse({
        items: [],
        page: { number: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
      })),
    )

    renderPage()

    expect(await screen.findByText('픽업 내역이 없습니다.')).toBeInTheDocument()
  })
})
