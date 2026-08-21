import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { PlatformOperatorAuthProvider } from '../../../../app/shells/platform-operator/PlatformOperatorAuthProvider'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { PaymentRecoveryCaseListPage } from './PaymentRecoveryCaseListPage'

describe('결제 복구 사건 목록', () => {
  it('복구 종류와 남은 환불액을 상세 링크로 표시한다', async () => {
    server.use(
      http.post('/api/v1/platform-operators/auth/refresh', () => successResponse({
        accessToken: 'operator-token', initialPasswordChangeRequired: false,
        sessionIdleExpiresAt: '2026-08-20T11:00:00Z', sessionAbsoluteExpiresAt: '2026-08-20T18:00:00Z',
      })),
      http.post('/api/v1/platform-operators/auth/token-refreshes', () => successResponse({
        accessToken: 'operator-token', initialPasswordChangeRequired: false,
        sessionIdleExpiresAt: '2026-08-20T11:00:00Z', sessionAbsoluteExpiresAt: '2026-08-20T18:00:00Z',
      })),
      http.get('/api/v1/platform-operators/payment-recovery-cases', () => successResponse({
        content: [{
          caseId: 'e6a91572-0632-4fa6-9a93-819add8df110', status: 'INVESTIGATING', caseVersion: 3,
          kind: 'REFUND_RESULT_UNKNOWN', resultStatus: 'UNKNOWN', originalAmountMinor: 50000,
          cumulativeRefundedAmountMinor: 10000, remainingRefundableAmountMinor: 40000,
          currency: 'KRW', maskedProviderReference: 'imp_****110', allowedActions: ['REQUERY_PROVIDER_RESULT'],
          handoffVersion: 2, paymentVersion: 4, recoveryVersion: 1, assignedOperatorId: 7,
          createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-20T09:30:00Z',
        }], page: 0, size: 20, totalElements: 1, totalPages: 1,
      })),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <PlatformOperatorAuthProvider><MemoryRouter><PaymentRecoveryCaseListPage /></MemoryRouter></PlatformOperatorAuthProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('환불 결과 불명')).toBeInTheDocument()
    expect(screen.getByText('남은 환불 가능액 40,000 KRW')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '사건 상세' })).toHaveAttribute(
      'href', '/admin/payment-recovery-cases/e6a91572-0632-4fa6-9a93-819add8df110',
    )
    expect(screen.queryByRole('button', { name: '나에게 배정' })).not.toBeInTheDocument()
  })

  it('담당 배정 전에 재인증을 요구한다', async () => {
    server.use(
      http.post('/api/v1/platform-operators/auth/refresh', () => successResponse({
        accessToken: 'operator-token', initialPasswordChangeRequired: false,
        sessionIdleExpiresAt: '2026-08-20T11:00:00Z', sessionAbsoluteExpiresAt: '2026-08-20T18:00:00Z',
      })),
      http.get('/api/v1/platform-operators/payment-recovery-cases', () => successResponse({
        content: [{
          caseId: 'e6a91572-0632-4fa6-9a93-819add8df110', status: 'INVESTIGATING', caseVersion: 3,
          kind: 'REFUND_RESULT_UNKNOWN', resultStatus: 'UNKNOWN', originalAmountMinor: 50000,
          cumulativeRefundedAmountMinor: 10000, remainingRefundableAmountMinor: 40000,
          currency: 'KRW', maskedProviderReference: null, allowedActions: ['RETRY_REFUND'],
          handoffVersion: 2, paymentVersion: 4, recoveryVersion: 1, assignedOperatorId: null,
          createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-20T09:30:00Z',
        }], page: 0, size: 20, totalElements: 1, totalPages: 1,
      })),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <PlatformOperatorAuthProvider><MemoryRouter><PaymentRecoveryCaseListPage /></MemoryRouter></PlatformOperatorAuthProvider>
      </QueryClientProvider>,
    )

    fireEvent.click(await screen.findByRole('button', { name: '나에게 배정' }))
    expect(screen.getByRole('dialog', { name: '재인증이 필요합니다' })).toBeInTheDocument()
  })

  it('별도 승인자에게 자기 제안을 제외한 승인 대기 사건의 상세 진입을 제공한다', async () => {
    server.use(
      http.post('/api/v1/platform-operators/auth/refresh', () => successResponse({
        accessToken: 'approver-token', initialPasswordChangeRequired: false,
        sessionIdleExpiresAt: '2026-08-20T11:00:00Z', sessionAbsoluteExpiresAt: '2026-08-20T18:00:00Z',
      })),
      http.get('/api/v1/platform-operators/payment-recovery-cases/pending-additional-approvals', () => successResponse({
        content: [{
          caseId: 'e6a91572-0632-4fa6-9a93-819add8df110', status: 'ADDITIONAL_APPROVAL_PENDING', caseVersion: 4,
          kind: 'REFUND_FAILED', resultStatus: 'FAILED', originalAmountMinor: 300000,
          cumulativeRefundedAmountMinor: 0, remainingRefundableAmountMinor: 300000,
          currency: 'KRW', maskedProviderReference: null, allowedActions: ['RETRY_REFUND'],
          handoffVersion: 2, paymentVersion: 4, recoveryVersion: 1, assignedOperatorId: 7,
          createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-20T09:30:00Z',
          proposals: [{ proposalVersion: 1, action: 'RETRY_REFUND', requestedAmountMinor: 250000,
            cumulativeLineageAmountMinor: 250000, originalAmountMinor: 300000, currency: 'KRW',
            approvalTier: 'ADDITIONAL_SUPER_ADMIN', requesterOperatorId: 7, approverOperatorId: null,
            createdAt: '2026-08-20T09:30:00Z' }], executions: [],
        }], page: 0, size: 20, totalElements: 1, totalPages: 1,
      })),
    )
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <PlatformOperatorAuthProvider><MemoryRouter><PaymentRecoveryCaseListPage approvalsOnly /></MemoryRouter></PlatformOperatorAuthProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('환불 실패')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '사건 상세' })).toHaveAttribute(
      'href', '/admin/payment-recovery-cases/e6a91572-0632-4fa6-9a93-819add8df110',
    )
    expect(screen.queryByRole('button', { name: '나에게 배정' })).not.toBeInTheDocument()
  })
})
