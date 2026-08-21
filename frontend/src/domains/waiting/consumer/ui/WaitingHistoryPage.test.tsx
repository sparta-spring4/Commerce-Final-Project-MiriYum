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
  it('종결 scope의 cursor 계약으로 본인 웨이팅 이력을 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get('/api/v1/consumers/me/waiting-teams', ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('scope')).toBe('TERMINAL')
        expect(url.searchParams.get('size')).toBe('20')
        return successResponse({
        items: [{
          waitingTeamId: '300', storeId: '100', status: 'RESERVATION_CONVERTED',
          registeredAt: '2026-08-17T00:00:00Z', calledAt: '2026-08-17T00:10:00Z',
          terminatedAt: '2026-08-17T00:20:00Z',
        }],
        nextCursor: null,
      })
      }),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <ConsumerAuthProvider><MemoryRouter><WaitingHistoryPage /></MemoryRouter></ConsumerAuthProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('예약 전환 완료')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '매장 #100' })).toBeInTheDocument()
    expect(screen.getByText(/9:20/)).toHaveAttribute('dateTime', '2026-08-17T00:20:00Z')
  })
})
