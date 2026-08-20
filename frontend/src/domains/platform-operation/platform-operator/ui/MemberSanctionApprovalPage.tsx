import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { isApiError, isNetworkError } from '../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../shared/api/envelope'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { TextField } from '../../../../shared/ui/Field'
import {
  Alert,
  EmptyState,
  ErrorState,
  Loading,
} from '../../../../shared/ui/Feedback'
import { Pagination } from '../../../../shared/ui/Pagination'
import { AuthErrorCode } from '../../../../shared/auth/authErrors'
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../../account/platform-operator/auth'
import {
  approvePermanentSanction,
  fetchPendingSanctionApprovals,
  pendingSanctionApprovalQueryKeys,
  type AccountType,
  type PendingSanctionApproval,
} from '../api/memberSupportApi'
import { accountTargetType } from '../api/reauthenticationApi'
import { useLogicalCommandAttempt } from './OperatorCommandFields'
import { OperatorAccessDenied } from './OperatorAccessDenied'
import { ReauthenticationDialog } from './ReauthenticationDialog'
import './page.css'

const PAGE_SIZE = 20
const REASON_CODE_PATTERN = /^[A-Z0-9_]+$/

const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  CONSUMER: '일반 사용자',
  STORE_OPERATOR: '매장 운영자',
}

/** 서버가 승인 후보로 판정한 영구 정지 제안만 선택해 추가 승인한다. */
export function MemberSanctionApprovalPage() {
  const { apiClient, capabilities, currentCapabilities } =
    usePlatformOperatorAuth()
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<PendingSanctionApproval | null>(null)
  const [reasonCode, setReasonCode] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [awaitingReauthentication, setAwaitingReauthentication] =
    useState(false)
  const commandAccessDeniedRef = useRef(true)

  const decision = decideCapability(
    capabilities,
    'ACCOUNT_PERMANENT_SANCTION_APPROVE',
  )
  const isSuperAdmin =
    currentCapabilities?.roles.includes('SUPER_ADMIN') === true

  const pendingQuery = useQuery({
    queryKey: pendingSanctionApprovalQueryKeys.list(page, PAGE_SIZE),
    queryFn: ({ signal }) =>
      fetchPendingSanctionApprovals(apiClient, page, PAGE_SIZE, signal),
    enabled: decision === 'allowed' && isSuperAdmin,
  })
  const deniedByServer =
    pendingQuery.isError &&
    isApiError(pendingQuery.error) &&
    pendingQuery.error.status === 403
  commandAccessDeniedRef.current =
    decision !== 'allowed' || !isSuperAdmin || deniedByServer

  const { attempt, beginAttempt, clearAttempt, isAttemptCurrent } =
    useLogicalCommandAttempt(
      () => ({ idempotencyKey: createIdempotencyKey() }),
      JSON.stringify({
        sanctionId: selected?.sanctionId ?? null,
        version: selected?.version ?? null,
        accountType: selected?.accountType ?? null,
        accountId: selected?.accountId ?? null,
        reasonCode,
      }),
    )

  useEffect(() => {
    if (!deniedByServer) {
      return
    }

    // 권한 회수는 이전 성공 query의 cache와 진행 중인 명령 상태를 모두 폐기하는
    // 경계다. 화면을 다시 조회할 수 있게 되더라도 이 선택을 복원하지 않는다.
    clearAttempt()
    setSelected(null)
    setReasonCode('')
    setErrors({})
    setFormError(null)
    setResult(null)
    setAwaitingReauthentication(false)
  }, [deniedByServer])

  if (decision === 'denied' || (decision === 'allowed' && !isSuperAdmin)) {
    return (
      <Alert tone="warning" title="이 업무를 수행할 권한이 없습니다.">
        영구 정지 추가 승인에는 <code>SUPER_ADMIN</code> 역할과{' '}
        <code>ACCOUNT_PERMANENT_SANCTION_APPROVE</code> 권한이 모두 필요합니다.
      </Alert>
    )
  }

  if (deniedByServer) {
    return (
      <OperatorAccessDenied requiredPermission="ACCOUNT_PERMANENT_SANCTION_APPROVE" />
    )
  }

  function selectSanction(sanction: PendingSanctionApproval) {
    clearAttempt()
    setSelected(sanction)
    setReasonCode('')
    setErrors({})
    setFormError(null)
    setResult(null)
  }

  function handleRequestReauthentication(event: React.FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (reasonCode.trim().length === 0) {
      next.reasonCode = '승인 사유 코드를 입력해 주세요.'
    } else if (!REASON_CODE_PATTERN.test(reasonCode)) {
      next.reasonCode = '대문자·숫자·밑줄만 사용할 수 있습니다.'
    }
    setErrors(next)
    setFormError(null)
    setResult(null)
    if (selected === null || Object.keys(next).length > 0) {
      return
    }
    beginAttempt()
    setAwaitingReauthentication(true)
  }

  async function handleApproved(approval: string) {
    if (commandAccessDeniedRef.current) {
      setAwaitingReauthentication(false)
      clearAttempt()
      return
    }
    if (selected === null || attempt === null || !isAttemptCurrent()) {
      setAwaitingReauthentication(false)
      clearAttempt()
      setFormError(
        '재인증 중 승인 대상이나 입력이 변경됐습니다. 다시 선택해 제출해 주세요.',
      )
      return
    }

    const sanction = selected
    setAwaitingReauthentication(false)
    setSubmitting(true)
    setFormError(null)
    try {
      const approved = await approvePermanentSanction(apiClient, {
        sanctionId: sanction.sanctionId,
        version: sanction.version,
        reauthenticationApproval: approval,
        idempotencyKey: attempt.idempotencyKey,
        body: { decision: 'APPROVE', reasonCode: reasonCode.trim() },
      })
      setResult(
        `제재 ${approved.sanctionId}가 ${approved.status} 상태가 됐습니다.`,
      )
      clearAttempt()
      setSelected(null)
      setReasonCode('')
      await pendingQuery.refetch()
    } catch (error) {
      setFormError(approvalErrorMessage(error))
      if (isApiError(error) && error.status === 409) {
        clearAttempt()
        setSelected(null)
        setReasonCode('')
        await pendingQuery.refetch()
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section aria-labelledby="member-approval-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="member-approval-heading">
            영구 정지 추가 승인
          </h1>
          <p className="po-page__subtitle">
            서버가 확인한 승인 대기 제재를 선택하고 별도 재인증 후 승인합니다.
          </p>
        </div>
      </header>

      {result !== null && <Alert tone="info" title={result} />}
      {formError !== null && <Alert tone="error" title={formError} />}

      {pendingQuery.isPending && (
        <Loading label="승인 대기 제재를 불러오는 중입니다." />
      )}

      {pendingQuery.isError && (
        <ErrorState
          error={pendingQuery.error}
          onRetry={() => void pendingQuery.refetch()}
        />
      )}

      {pendingQuery.data !== undefined &&
        (pendingQuery.data.content.length === 0 ? (
          <EmptyState
            title="승인 대기 제재가 없습니다."
            description="현재 운영자가 추가 승인할 수 있는 영구 정지 제안이 없습니다."
          />
        ) : (
          <>
            <div className="po-table-scroll">
              <table className="po-table">
                <caption className="po-table__caption">
                  제안자 본인의 건을 제외한 영구 정지 추가 승인 대기 목록입니다.
                </caption>
                <thead>
                  <tr>
                    <th scope="col">제재 ID</th>
                    <th scope="col">대상</th>
                    <th scope="col">제안 사유</th>
                    <th scope="col">정책 version</th>
                    <th scope="col">제안 시각</th>
                    <th scope="col">선택</th>
                  </tr>
                </thead>
                <tbody>
                  {pendingQuery.data.content.map((sanction) => (
                    <tr key={sanction.sanctionId}>
                      <th scope="row">{sanction.sanctionId}</th>
                      <td>
                        {ACCOUNT_TYPE_LABEL[sanction.accountType]} ·{' '}
                        {sanction.accountId}
                      </td>
                      <td>{sanction.reasonCode}</td>
                      <td>{sanction.policyVersion}</td>
                      <td>{formatDateTime(sanction.proposedAt)}</td>
                      <td>
                        <Button
                          type="button"
                          variant="ghost"
                          disabled={awaitingReauthentication || submitting}
                          onClick={() => selectSanction(sanction)}
                          aria-label={`제재 ${sanction.sanctionId} 선택`}
                        >
                          선택
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <Pagination
              number={pendingQuery.data.page.number}
              totalPages={pendingQuery.data.page.totalPages}
              totalElements={pendingQuery.data.page.totalElements}
              hasNext={pendingQuery.data.page.hasNext}
              onChange={(nextPage) => {
                clearAttempt()
                setSelected(null)
                setReasonCode('')
                setPage(nextPage)
              }}
            />
          </>
        ))}

      {selected === null ? (
        pendingQuery.data !== undefined &&
        pendingQuery.data.content.length > 0 && (
          <Alert tone="info" title="승인할 제재를 선택해 주세요.">
            목록의 서버 snapshot을 선택해야 승인 사유와 재인증을 진행할 수
            있습니다.
          </Alert>
        )
      ) : (
        <section className="po-card" aria-labelledby="selected-sanction-heading">
          <h2 className="po-section__title" id="selected-sanction-heading">
            선택한 제재
          </h2>
          <dl className="po-detail">
            <div className="po-detail__row">
              <dt>제재 ID</dt>
              <dd>{selected.sanctionId}</dd>
            </div>
            <div className="po-detail__row">
              <dt>대상 계정</dt>
              <dd>
                {ACCOUNT_TYPE_LABEL[selected.accountType]} · {selected.accountId}
              </dd>
            </div>
            <div className="po-detail__row">
              <dt>현재 version</dt>
              <dd>{selected.version}</dd>
            </div>
          </dl>

          <form
            className="po-form"
            onSubmit={handleRequestReauthentication}
            aria-label="영구 정지 추가 승인"
            inert={awaitingReauthentication}
            noValidate
          >
            <TextField
              label="승인 사유 코드"
              name="reasonCode"
              help="대문자·숫자·밑줄만 사용합니다."
              value={reasonCode}
              error={errors.reasonCode ?? null}
              onChange={(event) => setReasonCode(event.target.value)}
            />

            <Button
              type="submit"
              variant="primary"
              size="lg"
              loading={submitting}
              disabled={awaitingReauthentication}
            >
              영구 정지 승인
            </Button>
          </form>
        </section>
      )}

      {awaitingReauthentication && attempt !== null && selected !== null && (
        <ReauthenticationDialog
          purpose="PERMANENT_ACCOUNT_SANCTION_APPROVAL"
          targetType={accountTargetType(selected.accountType)}
          targetId={selected.accountId}
          description={`제재 ${selected.sanctionId}를 승인합니다. 본인 확인이 필요합니다.`}
          onApproved={handleApproved}
          onCancel={() => setAwaitingReauthentication(false)}
        />
      )}
    </section>
  )
}

function formatDateTime(isoTimestamp: string): string {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) {
    return isoTimestamp
  }
  return parsed.toLocaleString('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

function approvalErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 제재 상태를 다시 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '승인하지 못했습니다.'
  }
  if (error.status === 403) {
    return '승인 권한이 없거나 제안자 본인이라 승인할 수 없습니다.'
  }
  if (error.status === 404) {
    return '해당 제재를 찾을 수 없습니다. 목록을 다시 확인해 주세요.'
  }
  switch (error.code) {
    case AuthErrorCode.PERMANENT_SANCTION_APPROVAL_CONFLICT:
      return '이미 승인됐거나 종결된 제안입니다. 목록을 갱신했습니다.'
    case AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT:
      return '제재 상태가 변경됐습니다. 목록을 갱신했습니다.'
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '같은 키로 다른 내용을 보냈습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.'
    default:
      return error.message
  }
}
