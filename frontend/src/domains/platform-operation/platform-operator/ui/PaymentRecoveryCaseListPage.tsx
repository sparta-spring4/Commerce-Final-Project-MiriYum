import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { PLATFORM_OPERATOR_PATHS } from '../../../../app/routes/paths/platformOperatorPaths'
import { usePlatformOperatorAuth } from '../../../../app/shells/platform-operator/PlatformOperatorAuthProvider'
import { useIdempotentAttempt } from '../../../../shared/api/useIdempotentAttempt'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { Alert } from '../../../../shared/ui/Feedback'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Pagination } from '../../../../shared/ui/Pagination'
import { assignPaymentRecoveryCase, usePaymentRecoveryCases, usePendingPaymentRecoveryApprovals, type PaymentRecoveryCaseSummary } from '../api/paymentRecoveryApi'
import { ReauthenticationDialog } from './ReauthenticationDialog'

const KIND: Record<PaymentRecoveryCaseSummary['kind'], string> = {
  DISPOSITION_RESULT_UNKNOWN: '처분 결과 불명', DISPOSITION_FAILED: '처분 실패',
  REFUND_RESULT_UNKNOWN: '환불 결과 불명', REFUND_FAILED: '환불 실패',
}

export function PaymentRecoveryCaseListPage({ approvalsOnly = false }: { approvalsOnly?: boolean }) {
  const { apiClient } = usePlatformOperatorAuth()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [assignment, setAssignment] = useState<PaymentRecoveryCaseSummary | null>(null)
  const [assigning, setAssigning] = useState(false)
  const [assignError, setAssignError] = useState<string | null>(null)
  const parsed = Number.parseInt(searchParams.get('page') ?? '', 10)
  const page = Number.isInteger(parsed) && parsed > 0 ? parsed : 0
  const regularCases = usePaymentRecoveryCases(undefined, page, !approvalsOnly)
  const pendingApprovals = usePendingPaymentRecoveryApprovals(page, approvalsOnly)
  const cases = approvalsOnly ? pendingApprovals : regularCases
  const assignmentAttempt = useIdempotentAttempt(assignment === null
    ? 'payment-recovery-assignment:none'
    : `payment-recovery-assignment:${assignment.caseId}:${assignment.caseVersion}`)

  return (
    <main className="po-page">
      <header className="po-page__head"><h1>{approvalsOnly ? '결제 복구 추가 승인' : '결제 복구 사건'}</h1><p>{approvalsOnly ? '다른 운영자가 제안한 고액 복구 중 추가 승인 가능한 사건만 확인합니다.' : '결과 불명·실패 결제를 조사하고 복구 상태를 확인합니다.'}</p></header>
      {cases.isPending && <Loading label="결제 복구 사건을 불러오는 중입니다." />}
      {cases.isError && <ErrorState error={cases.error} onRetry={() => void cases.refetch()} />}
      {cases.isSuccess && cases.data.content.length === 0 && <EmptyState title="결제 복구 사건이 없습니다." />}
      {cases.isSuccess && cases.data.content.length > 0 && (
        <>
          <ul className="po-recovery-list">
            {cases.data.content.map((item) => (
              <li key={item.caseId} className="mi-card"><div className="mi-card__body">
                <div className="po-recovery-list__head"><Badge tone="attention">{item.status}</Badge><span>{KIND[item.kind]}</span></div>
                <p>남은 환불 가능액 {item.remainingRefundableAmountMinor.toLocaleString('ko-KR')} {item.currency}</p>
                <small>{item.maskedProviderReference ?? '결제사 참조 없음'}</small>
                <div className="po-recovery-list__actions">
                  {!approvalsOnly && item.assignedOperatorId == null ? (
                    <Button type="button" variant="secondary" loading={assigning && assignment?.caseId === item.caseId} onClick={() => { setAssignError(null); setAssignment(item) }}>나에게 배정</Button>
                  ) : approvalsOnly || item.assignedToCurrentOperator ? (
                    <Link className="mi-button mi-button--ghost" to={PLATFORM_OPERATOR_PATHS.paymentRecoveryDetail.replace(':caseId', item.caseId)}>사건 상세</Link>
                  ) : (
                    <span>다른 운영자 처리 중</span>
                  )}
                </div>
              </div></li>
            ))}
          </ul>
          <Pagination number={cases.data.page} totalPages={cases.data.totalPages} totalElements={cases.data.totalElements} hasNext={page + 1 < cases.data.totalPages} onChange={(next) => {
            const params = new URLSearchParams(); if (next > 0) params.set('page', String(next)); setSearchParams(params)
          }} />
        </>
      )}
      {assignError !== null && <Alert tone="error" title={assignError} />}
      {assignment !== null && (
        <ReauthenticationDialog
          purpose="PAYMENT_RECOVERY"
          targetType="PAYMENT_RECOVERY_CASE"
          targetId={assignment.caseId}
          description="결제 복구 사건을 담당하려면 본인 확인이 필요합니다."
          onCancel={() => setAssignment(null)}
          onApproved={async (approval) => {
            const currentAssignment = assignment
            const idempotencyKey = assignmentAttempt.begin()
            if (idempotencyKey === null) {
              setAssignError('앞선 배정 결과를 확인할 수 없습니다. 최신 사건 상태를 확인해 주세요.')
              setAssignment(null)
              await regularCases.refetch()
              return
            }
            setAssigning(true)
            try {
              await assignPaymentRecoveryCase(apiClient, {
                caseId: currentAssignment.caseId,
                caseVersion: currentAssignment.caseVersion,
                approval,
                idempotencyKey,
                correlationId: idempotencyKey,
              })
              assignmentAttempt.settle(null)
              navigate(PLATFORM_OPERATOR_PATHS.paymentRecoveryDetail.replace(':caseId', currentAssignment.caseId))
            } catch (error) {
              assignmentAttempt.settle(error)
              const refreshed = await regularCases.refetch()
              const latest = refreshed.data?.content.find((item) => item.caseId === currentAssignment.caseId)
              if (latest?.assignedToCurrentOperator) {
                assignmentAttempt.settle(null)
                navigate(PLATFORM_OPERATOR_PATHS.paymentRecoveryDetail.replace(':caseId', currentAssignment.caseId))
              } else {
                if (latest !== undefined && (latest.caseVersion !== currentAssignment.caseVersion || latest.assignedOperatorId !== null)) {
                  assignmentAttempt.settle(null)
                }
                setAssignError(error instanceof Error ? error.message : '담당 배정에 실패했습니다.')
              }
            } finally {
              setAssigning(false)
              setAssignment(null)
            }
          }}
        />
      )}
    </main>
  )
}
