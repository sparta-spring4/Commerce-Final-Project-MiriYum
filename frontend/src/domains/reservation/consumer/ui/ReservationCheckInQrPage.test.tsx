import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ConsumerAuthProvider } from '../../../account/consumer/auth'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { ReservationCheckInQrPage } from './ReservationCheckInQrPage'

describe('예약 체크인 QR', () => {
  it('화면 진입 시 30초 grant를 발급하고 재발급 시간을 알린다', async () => {
    server.use(
      authenticatedConsumer(),
      http.post('/api/v1/consumers/me/reservations/901/check-in-qr-grants', () =>
        successResponse({
          reservationId: '901', qrToken: 'v1.opaque-credential', tokenVersion: 4,
          issuedAt: '2026-08-20T10:00:00Z', expiresAt: '2026-08-20T10:00:30Z',
        }),
      ),
    )
    const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <ConsumerAuthProvider>
          <MemoryRouter initialEntries={['/reservations/901/check-in']}>
            <Routes><Route path="/reservations/:reservationId/check-in" element={<ReservationCheckInQrPage />} /></Routes>
          </MemoryRouter>
        </ConsumerAuthProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByLabelText('매장 체크인 QR')).toBeInTheDocument()
    expect(screen.getByText('보안을 위해 QR은 자동으로 갱신됩니다.')).toBeInTheDocument()
    expect(screen.queryByText('v1.opaque-credential')).not.toBeInTheDocument()
  })
})
