import { fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { PlatformOperatorAuthProvider } from '../../../../app/shells/platform-operator/PlatformOperatorAuthProvider'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { PaymentRecoveryCaseDetailPage } from './PaymentRecoveryCaseDetailPage'

describe('결제 복구 사건 상세', () => {
  it('환불 재시도 제안 전에 재인증을 요구한다', async () => {
    server.use(
      http.post('/api/v1/platform-operators/auth/token-refreshes', () => successResponse({
        accessToken: 'operator-token', initialPasswordChangeRequired: false,
        sessionIdleExpiresAt: '2026-08-20T11:00:00Z', sessionAbsoluteExpiresAt: '2026-08-20T18:00:00Z',
      })),
      http.get('/api/v1/platform-operators/payment-recovery-cases/:caseId', () => successResponse({
        caseId: 'case-1', status: 'INVESTIGATING', caseVersion: 3,
        kind: 'REFUND_FAILED', resultStatus: 'FAILED', originalAmountMinor: 50000,
        cumulativeRefundedAmountMinor: 10000, remainingRefundableAmountMinor: 40000,
        currency: 'KRW', maskedProviderReference: null, allowedActions: ['RETRY_REFUND'],
        handoffVersion: 2, paymentVersion: 4, recoveryVersion: 1, assignedOperatorId: 7,
        createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-20T09:30:00Z',
        proposals: [], executions: [],
      })),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><PlatformOperatorAuthProvider><MemoryRouter initialEntries={['/admin/payment-recovery-cases/case-1']}><Routes><Route path="/admin/payment-recovery-cases/:caseId" element={<PaymentRecoveryCaseDetailPage />} /></Routes></MemoryRouter></PlatformOperatorAuthProvider></QueryClientProvider>)

    fireEvent.click(await screen.findByRole('button', { name: '환불 재시도 제안' }))
    expect(screen.getByRole('dialog', { name: '재인증이 필요합니다' })).toBeInTheDocument()
  })
})
