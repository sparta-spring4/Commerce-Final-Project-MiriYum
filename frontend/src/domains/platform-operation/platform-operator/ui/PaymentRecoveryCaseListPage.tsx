import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { PLATFORM_OPERATOR_PATHS } from '../../../../app/routes/paths/platformOperatorPaths'
import { usePlatformOperatorAuth } from '../../../../app/shells/platform-operator/PlatformOperatorAuthProvider'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { Alert } from '../../../../shared/ui/Feedback'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Pagination } from '../../../../shared/ui/Pagination'
import { assignPaymentRecoveryCase, usePaymentRecoveryCases, type PaymentRecoveryCaseSummary } from '../api/paymentRecoveryApi'
import { ReauthenticationDialog } from './ReauthenticationDialog'

const KIND: Record<PaymentRecoveryCaseSummary['kind'], string> = {
  DISPOSITION_RESULT_UNKNOWN: '처분 결과 불명', DISPOSITION_FAILED: '처분 실패',
  REFUND_RESULT_UNKNOWN: '환불 결과 불명', REFUND_FAILED: '환불 실패',
}

export function PaymentRecoveryCaseListPage() {
  const { apiClient } = usePlatformOperatorAuth()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [assignment, setAssignment] = useState<PaymentRecoveryCaseSummary | null>(null)
  const [assigning, setAssigning] = useState(false)
  const [assignError, setAssignError] = useState<string | null>(null)
  const parsed = Number.parseInt(searchParams.get('page') ?? '', 10)
  const page = Number.isInteger(parsed) && parsed > 0 ? parsed : 0
  const cases = usePaymentRecoveryCases(undefined, page)

  return (
    <main className="po-page">
      <header className="po-page__head"><h1>결제 복구 사건</h1><p>결과 불명·실패 결제를 조사하고 복구 상태를 확인합니다.</p></header>
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
                  {item.assignedOperatorId == null ? (
                    <Button type="button" variant="secondary" loading={assigning && assignment?.caseId === item.caseId} onClick={() => { setAssignError(null); setAssignment(item) }}>나에게 배정</Button>
                  ) : (
                    <Link className="mi-button mi-button--ghost" to={PLATFORM_OPERATOR_PATHS.paymentRecoveryDetail.replace(':caseId', item.caseId)}>사건 상세</Link>
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
            setAssigning(true)
            try {
              await assignPaymentRecoveryCase(apiClient, {
                caseId: assignment.caseId,
                caseVersion: assignment.caseVersion,
                approval,
                idempotencyKey: createIdempotencyKey(),
                correlationId: createIdempotencyKey(),
              })
              navigate(PLATFORM_OPERATOR_PATHS.paymentRecoveryDetail.replace(':caseId', assignment.caseId))
            } catch (error) {
              setAssignError(error instanceof Error ? error.message : '담당 배정에 실패했습니다.')
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
