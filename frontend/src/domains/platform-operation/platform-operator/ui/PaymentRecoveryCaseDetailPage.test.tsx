import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
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
        assignedToCurrentOperator: true,
        canApproveAdditionalProposal: false,
        createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-20T09:30:00Z',
        proposals: [], executions: [],
      })),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><PlatformOperatorAuthProvider><MemoryRouter initialEntries={['/admin/payment-recovery-cases/case-1']}><Routes><Route path="/admin/payment-recovery-cases/:caseId" element={<PaymentRecoveryCaseDetailPage />} /></Routes></MemoryRouter></PlatformOperatorAuthProvider></QueryClientProvider>)

    fireEvent.click(await screen.findByRole('button', { name: '환불 재시도 제안' }))
    expect(screen.getByRole('dialog', { name: '재인증이 필요합니다' })).toBeInTheDocument()
  })

  it('결과 불명 사건의 결제사 결과 재조회 전에 재인증을 요구한다', async () => {
    server.use(
      http.post('/api/v1/platform-operators/auth/token-refreshes', () => successResponse({
        accessToken: 'operator-token', initialPasswordChangeRequired: false,
        sessionIdleExpiresAt: '2026-08-20T11:00:00Z', sessionAbsoluteExpiresAt: '2026-08-20T18:00:00Z',
      })),
      http.get('/api/v1/platform-operators/payment-recovery-cases/:caseId', () => successResponse({
        caseId: 'case-1', status: 'INVESTIGATING', caseVersion: 3,
        kind: 'REFUND_RESULT_UNKNOWN', resultStatus: 'UNKNOWN', originalAmountMinor: 50000,
        cumulativeRefundedAmountMinor: 10000, remainingRefundableAmountMinor: 40000,
        currency: 'KRW', maskedProviderReference: null, allowedActions: ['REQUERY_PROVIDER_RESULT'],
        handoffVersion: 2, paymentVersion: 4, recoveryVersion: 1, assignedOperatorId: 7,
        assignedToCurrentOperator: true,
        canApproveAdditionalProposal: false,
        createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-20T09:30:00Z',
        proposals: [], executions: [],
      })),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><PlatformOperatorAuthProvider><MemoryRouter initialEntries={['/admin/payment-recovery-cases/case-1']}><Routes><Route path="/admin/payment-recovery-cases/:caseId" element={<PaymentRecoveryCaseDetailPage />} /></Routes></MemoryRouter></PlatformOperatorAuthProvider></QueryClientProvider>)

    fireEvent.click(await screen.findByRole('button', { name: '결제사 결과 재조회' }))
    expect(screen.getByRole('dialog', { name: '재인증이 필요합니다' })).toBeInTheDocument()
  })

  it('응답 유실 뒤 같은 복구 제안을 같은 멱등 키로 재시도한다', async () => {
    const idempotencyKeys: string[] = []
    let attempts = 0
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
        assignedToCurrentOperator: true,
        canApproveAdditionalProposal: false,
        createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-20T09:30:00Z',
        proposals: [], executions: [],
      })),
      http.post('/api/v1/platform-operators/reauthentication-approvals', () => successResponse({
        approval: `approval-${attempts + 1}`, expiresAt: '2026-08-20T10:05:00Z',
      })),
      http.post('/api/v1/platform-operators/payment-recovery-cases/:caseId/proposals', ({ request }) => {
        idempotencyKeys.push(request.headers.get('Idempotency-Key') ?? '')
        attempts += 1
        return attempts === 1 ? HttpResponse.error() : successResponse({ proposalVersion: 1 })
      }),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><PlatformOperatorAuthProvider><MemoryRouter initialEntries={['/admin/payment-recovery-cases/case-1']}><Routes><Route path="/admin/payment-recovery-cases/:caseId" element={<PaymentRecoveryCaseDetailPage />} /></Routes></MemoryRouter></PlatformOperatorAuthProvider></QueryClientProvider>)

    for (let attempt = 0; attempt < 2; attempt += 1) {
      fireEvent.click(await screen.findByRole('button', { name: '환불 재시도 제안' }))
      fireEvent.change(screen.getByLabelText('현재 비밀번호'), { target: { value: 'Miriyum1!' } })
      fireEvent.click(screen.getByRole('button', { name: '확인' }))
      await waitFor(() => expect(idempotencyKeys).toHaveLength(attempt + 1))
    }

    expect(idempotencyKeys[0]).toBeTruthy()
    expect(idempotencyKeys[1]).toBe(idempotencyKeys[0])
  })

  it('서버가 추가 승인 불가로 판정한 제안에는 승인 버튼을 노출하지 않는다', async () => {
    server.use(
      http.post('/api/v1/platform-operators/auth/token-refreshes', () => successResponse({
        accessToken: 'operator-token', initialPasswordChangeRequired: false,
        sessionIdleExpiresAt: '2026-08-20T11:00:00Z', sessionAbsoluteExpiresAt: '2026-08-20T18:00:00Z',
      })),
      http.get('/api/v1/platform-operators/payment-recovery-cases/:caseId', () => successResponse({
        caseId: 'case-1', status: 'ADDITIONAL_APPROVAL_PENDING', caseVersion: 3,
        kind: 'REFUND_FAILED', resultStatus: 'FAILED', originalAmountMinor: 50000,
        cumulativeRefundedAmountMinor: 10000, remainingRefundableAmountMinor: 40000,
        currency: 'KRW', maskedProviderReference: null, allowedActions: ['RETRY_REFUND'],
        handoffVersion: 2, paymentVersion: 4, recoveryVersion: 1, assignedOperatorId: 7,
        assignedToCurrentOperator: true, canApproveAdditionalProposal: false,
        createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-20T09:30:00Z',
        proposals: [{
          proposalVersion: 1, action: 'RETRY_REFUND', requestedAmountMinor: 40000,
          cumulativeLineageAmountMinor: 40000, originalAmountMinor: 50000, currency: 'KRW',
          approvalTier: 'ADDITIONAL_SUPER_ADMIN', requesterOperatorId: 7,
          approverOperatorId: null, createdAt: '2026-08-20T09:20:00Z',
        }],
        executions: [],
      })),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><PlatformOperatorAuthProvider><MemoryRouter initialEntries={['/admin/payment-recovery-cases/case-1']}><Routes><Route path="/admin/payment-recovery-cases/:caseId" element={<PaymentRecoveryCaseDetailPage />} /></Routes></MemoryRouter></PlatformOperatorAuthProvider></QueryClientProvider>)

    expect(await screen.findByText('RETRY_REFUND · ADDITIONAL_SUPER_ADMIN')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '추가 승인' })).not.toBeInTheDocument()
  })
})
