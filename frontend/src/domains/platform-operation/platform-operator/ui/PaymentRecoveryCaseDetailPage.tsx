import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { PLATFORM_OPERATOR_PATHS } from '../../../../app/routes/paths/platformOperatorPaths'
import { usePlatformOperatorAuth } from '../../../../app/shells/platform-operator/PlatformOperatorAuthProvider'
import { Button } from '../../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { useIdempotentAttempt } from '../../../../shared/api/useIdempotentAttempt'
import {
  approvePaymentRecoveryProposal,
  proposePaymentRecoveryRefund,
  requeryPaymentRecoveryProviderResult,
  usePaymentRecoveryCase,
  type PaymentRecoveryCaseDetail,
} from '../api/paymentRecoveryApi'
import { ReauthenticationDialog } from './ReauthenticationDialog'

type PendingCommand =
  | { kind: 'REQUERY' }
  | { kind: 'PROPOSE' }
  | { kind: 'APPROVE'; proposalVersion: number }

export function PaymentRecoveryCaseDetailPage() {
  const { apiClient } = usePlatformOperatorAuth()
  const { caseId = '' } = useParams()
  const detail = usePaymentRecoveryCase(caseId)
  const [pending, setPending] = useState<PendingCommand | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [commandError, setCommandError] = useState<string | null>(null)
  const item = detail.data
  const commandAttempt = useIdempotentAttempt(commandFingerprint(item, pending))

  if (detail.isPending) return <Loading label="복구 사건 상세를 불러오는 중입니다." />
  if (detail.isError) return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
  if (item === undefined) return <Loading label="복구 사건 상세를 확인하는 중입니다." />
  const currentItem: PaymentRecoveryCaseDetail = item

  async function handleApproved(approval: string) {
    if (pending === null) return
    const idempotencyKey = commandAttempt.begin()
    if (idempotencyKey === null) {
      setCommandError('사건 상태가 변경되었습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요.')
      setPending(null)
      await detail.refetch()
      return
    }
    setSubmitting(true)
    setCommandError(null)
    try {
      if (pending.kind === 'REQUERY') {
        await requeryPaymentRecoveryProviderResult(
          apiClient, currentItem, approval, idempotencyKey, idempotencyKey,
        )
      } else if (pending.kind === 'PROPOSE') {
        await proposePaymentRecoveryRefund(
          apiClient, currentItem, approval, idempotencyKey, idempotencyKey,
        )
      } else {
        await approvePaymentRecoveryProposal(
          apiClient, currentItem, pending.proposalVersion, approval, idempotencyKey, idempotencyKey,
        )
      }
      setPending(null)
      await detail.refetch()
      commandAttempt.settle(null)
    } catch (error) {
      setCommandError(error instanceof Error ? error.message : '복구 명령을 처리하지 못했습니다.')
      const refreshed = await detail.refetch()
      commandAttempt.settle(
        refreshed.data !== undefined && snapshotFingerprint(refreshed.data) !== snapshotFingerprint(currentItem)
          ? null
          : error,
      )
      setPending(null)
    } finally {
      setSubmitting(false)
    }
  }

  return <main className="po-page">
    <p><Link to={PLATFORM_OPERATOR_PATHS.paymentRecoveryCases}>결제 복구 사건으로 돌아가기</Link></p>
    <header className="po-page__head"><h1>결제 복구 사건 상세</h1><p>{item.caseId}</p></header>
    {commandError !== null && <Alert tone="error" title={commandError} />}
    <section className="mi-card"><div className="mi-card__body">
      <dl className="po-recovery-detail">
        <div><dt>상태</dt><dd>{item.status}</dd></div><div><dt>결과</dt><dd>{item.resultStatus}</dd></div>
        <div><dt>원 결제액</dt><dd>{money(item.originalAmountMinor, item.currency)}</dd></div>
        <div><dt>남은 환불 가능액</dt><dd>{money(item.remainingRefundableAmountMinor, item.currency)}</dd></div>
        <div><dt>담당 운영자</dt><dd>{item.assignedOperatorId ?? '미배정'}</dd></div>
      </dl>
    </div></section>

    {item.assignedToCurrentOperator && item.allowedActions.includes('RETRY_REFUND') && (
      <section className="po-recovery-command">
        <h2>환불 복구</h2>
        <p>현재 결제·환불 버전을 기준으로 환불 재시도를 제안합니다. 금액은 서버가 결제 원장으로 검증합니다.</p>
        <Button type="button" variant="primary" loading={submitting} onClick={() => setPending({ kind: 'PROPOSE' })}>환불 재시도 제안</Button>
      </section>
    )}

    {item.assignedToCurrentOperator && item.allowedActions.includes('REQUERY_PROVIDER_RESULT') && (
      <section className="po-recovery-command">
        <h2>결제사 결과 재조회</h2>
        <p>결제사 명령을 다시 보내지 않고 서버가 기존 처리 결과만 안전하게 조회합니다.</p>
        <Button type="button" variant="primary" loading={submitting} onClick={() => setPending({ kind: 'REQUERY' })}>결제사 결과 재조회</Button>
      </section>
    )}

    <section><h2>복구 제안</h2>{item.proposals.length === 0 ? <p>등록된 제안이 없습니다.</p> : <ul className="po-recovery-history">{item.proposals.map((proposal) => <li key={proposal.proposalVersion} className="mi-card"><div className="mi-card__body"><p>{proposal.action} · {proposal.approvalTier}</p><p>{money(proposal.requestedAmountMinor, proposal.currency)}</p>{needsAdditionalApproval(item, proposal) && <Button type="button" variant="secondary" loading={submitting} onClick={() => setPending({ kind: 'APPROVE', proposalVersion: proposal.proposalVersion })}>추가 승인</Button>}</div></li>)}</ul>}</section>
    <section><h2>실행 이력</h2>{item.executions.length === 0 ? <p>실행 이력이 없습니다.</p> : <ul className="po-recovery-history">{item.executions.map((execution) => <li key={execution.executionId}>{execution.operation} · {execution.status}</li>)}</ul>}</section>

    {pending !== null && (
      <ReauthenticationDialog
        purpose="PAYMENT_RECOVERY"
        targetType="PAYMENT_RECOVERY_CASE"
        targetId={pending.kind === 'APPROVE' ? `${item.caseId}:proposal:${pending.proposalVersion}` : item.caseId}
        description={pending.kind === 'APPROVE' ? '고액 환불 재시도를 추가 승인하려면 본인 확인이 필요합니다.' : pending.kind === 'REQUERY' ? '기존 결제사 처리 결과를 다시 조회하려면 본인 확인이 필요합니다.' : '환불 재시도를 제안하려면 본인 확인이 필요합니다.'}
        onApproved={handleApproved}
        onCancel={() => setPending(null)}
      />
    )}
  </main>
}

function commandFingerprint(
  item: PaymentRecoveryCaseDetail | undefined,
  pending: PendingCommand | null,
) {
  if (item === undefined || pending === null) return 'none'
  return JSON.stringify({
    caseId: item.caseId,
    caseVersion: item.caseVersion,
    handoffVersion: item.handoffVersion,
    paymentVersion: item.paymentVersion,
    recoveryVersion: item.recoveryVersion,
    command: pending,
  })
}

function snapshotFingerprint(item: PaymentRecoveryCaseDetail) {
  return JSON.stringify({
    caseVersion: item.caseVersion,
    status: item.status,
    handoffVersion: item.handoffVersion,
    paymentVersion: item.paymentVersion,
    recoveryVersion: item.recoveryVersion,
  })
}

function needsAdditionalApproval(
  item: PaymentRecoveryCaseDetail,
  proposal: PaymentRecoveryCaseDetail['proposals'][number],
) {
  return item.canApproveAdditionalProposal && item.status === 'ADDITIONAL_APPROVAL_PENDING' && proposal.approvalTier === 'ADDITIONAL_SUPER_ADMIN' && proposal.approverOperatorId == null
}

function money(amount: number, currency: string) {
  return `${amount.toLocaleString('ko-KR')} ${currency}`
}
