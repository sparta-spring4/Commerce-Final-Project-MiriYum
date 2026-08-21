import { useQuery } from '@tanstack/react-query'
import type { ApiClient } from '../../../../shared/api/client'
import type { components } from '../../../../shared/api/generated/payment-recovery'
import { usePlatformOperatorAuth } from '../../../../app/shells/platform-operator/PlatformOperatorAuthProvider'
import { PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/platform-operator/querySession'

export type PaymentRecoveryCaseSummary = components['schemas']['CaseSummary']
export type PaymentRecoveryCaseDetail = components['schemas']['CaseDetail']
export type PendingPaymentRecoveryApprovalPage = components['schemas']['PendingApprovalPage']

export const paymentRecoveryKeys = {
  all: PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.paymentRecoveryCases,
  list: (status: string | undefined, page: number) =>
    [...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.paymentRecoveryCases, 'list', status ?? 'ALL', page] as const,
  detail: (caseId: string) =>
    [...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.paymentRecoveryCases, 'detail', caseId] as const,
  pendingApprovals: (page: number) =>
    [...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.paymentRecoveryCases, 'pending-approvals', page] as const,
}

export function usePaymentRecoveryCases(
  status: PaymentRecoveryCaseSummary['status'] | undefined,
  page: number,
  enabled = true,
) {
  const { apiClient } = usePlatformOperatorAuth()
  return useQuery({
    queryKey: paymentRecoveryKeys.list(status, page),
    queryFn: async ({ signal }) => {
      const response = await apiClient('/api/v1/platform-operators/payment-recovery-cases', {
        method: 'get', query: { status, page, size: 20 }, signal,
      })
      return response.data
    },
    enabled,
  })
}

export function usePendingPaymentRecoveryApprovals(page: number, enabled = true) {
  const { apiClient } = usePlatformOperatorAuth()
  return useQuery({
    queryKey: paymentRecoveryKeys.pendingApprovals(page),
    queryFn: async ({ signal }) => {
      const response = await apiClient(
        '/api/v1/platform-operators/payment-recovery-cases/pending-additional-approvals',
        { method: 'get', query: { page, size: 20 }, signal },
      )
      return response.data
    },
    enabled,
  })
}

export function usePaymentRecoveryCase(caseId: string) {
  const { apiClient } = usePlatformOperatorAuth()
  return useQuery({
    queryKey: paymentRecoveryKeys.detail(caseId),
    queryFn: async ({ signal }) => {
      const response = await apiClient('/api/v1/platform-operators/payment-recovery-cases/{caseId}', {
        method: 'get', pathParams: { caseId }, signal,
      })
      return response.data
    },
    enabled: caseId.length > 0,
  })
}

export async function assignPaymentRecoveryCase(
  apiClient: ApiClient,
  input: {
    caseId: string
    caseVersion: number
    approval: string
    idempotencyKey: string
    correlationId: string
  },
) {
  const response = await apiClient(
    '/api/v1/platform-operators/payment-recovery-cases/{caseId}/assignments',
    {
      method: 'post',
      pathParams: { caseId: input.caseId },
      body: { expectedCaseVersion: input.caseVersion },
      idempotencyKey: input.idempotencyKey,
      adminReauthentication: input.approval,
      correlationId: input.correlationId,
    },
  )
  return response.data
}

export async function proposePaymentRecoveryRefund(
  apiClient: ApiClient,
  item: PaymentRecoveryCaseDetail,
  approval: string,
  idempotencyKey: string,
  correlationId: string,
) {
  const response = await apiClient(
    '/api/v1/platform-operators/payment-recovery-cases/{caseId}/proposals',
    {
      method: 'post',
      pathParams: { caseId: item.caseId },
      body: {
        action: 'RETRY_REFUND',
        expectedCaseVersion: item.caseVersion,
        expectedHandoffVersion: item.handoffVersion,
        expectedPaymentVersion: item.paymentVersion,
        expectedRecoveryVersion: item.recoveryVersion,
      },
      idempotencyKey,
      correlationId,
      adminReauthentication: approval,
    },
  )
  return response.data
}

export async function approvePaymentRecoveryProposal(
  apiClient: ApiClient,
  item: PaymentRecoveryCaseDetail,
  proposalVersion: number,
  approval: string,
  idempotencyKey: string,
  correlationId: string,
) {
  const response = await apiClient(
    '/api/v1/platform-operators/payment-recovery-cases/{caseId}/proposals/{proposalVersion}/approvals',
    {
      method: 'post',
      pathParams: { caseId: item.caseId, proposalVersion },
      body: { expectedCaseVersion: item.caseVersion, expectedProposalVersion: proposalVersion },
      idempotencyKey,
      correlationId,
      adminReauthentication: approval,
    },
  )
  return response.data
}

export async function requeryPaymentRecoveryProviderResult(
  apiClient: ApiClient,
  item: PaymentRecoveryCaseDetail,
  approval: string,
  idempotencyKey: string,
  correlationId: string,
) {
  const response = await apiClient(
    '/api/v1/platform-operators/payment-recovery-cases/{caseId}/requeries',
    {
      method: 'post',
      pathParams: { caseId: item.caseId },
      body: {
        expectedCaseVersion: item.caseVersion,
        expectedHandoffVersion: item.handoffVersion,
        expectedPaymentVersion: item.paymentVersion,
        expectedRecoveryVersion: item.recoveryVersion,
      },
      idempotencyKey,
      correlationId,
      adminReauthentication: approval,
    },
  )
  return response.data
}
