import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ConsumerAuthProvider } from '../../../account/consumer/auth'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { WaitingHistoryPage } from './WaitingHistoryPage'

describe('지난 웨이팅 이력', () => {
  it('종결 상태와 예약 전환 링크를 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get('/api/v1/consumers/me/waiting-team-histories', () => successResponse({
        items: [{
          waitingTeamId: '300', storeId: '100', businessDate: '2026-08-17',
          status: 'RESERVATION_CONVERTED', queueSequence: 9, partySize: 2,
          createdAt: '2026-08-17T00:00:00Z', endedAt: '2026-08-17T00:20:00Z',
          reservationId: '901',
        }],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
      })),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <ConsumerAuthProvider><MemoryRouter><WaitingHistoryPage /></MemoryRouter></ConsumerAuthProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('예약 전환 완료')).toBeInTheDocument()
    expect(screen.getByText('2명 · 접수 순번 9번')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '전환된 예약 보기' })).toHaveAttribute(
      'href', '/reservations/901',
    )
  })
})
