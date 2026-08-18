import { useState } from 'react'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Button } from '../../../shared/ui/Button'
import { SelectField, TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { AuthErrorCode } from '../../auth/model/authErrors'
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../platform-operator-auth'
import {
  approvePermanentSanction,
  type AccountType,
} from '../api/memberSupportApi'
import { accountTargetType } from '../api/reauthenticationApi'
import { useLogicalCommandAttempt } from './OperatorCommandFields'
import { ReauthenticationDialog } from './ReauthenticationDialog'
import './page.css'

const REASON_CODE_PATTERN = /^[A-Z0-9_]+$/

/**
 * 회원 영구정지 추가 승인.
 *
 * **대기 목록을 만들지 않는다.** 승인 대기 제재를 조회하는 계약이 없기 때문이다
 * (#425). 목록을 흉내 내면 실제로는 없는 데이터를 보여 주게 된다.
 *
 * 대신 제안자가 전달한 대상 계정 정보와 sanction ID, version으로 진입하는
 * 형태로 둔다. 제안 화면이 그 값을 복사할 수 있게 제공하므로, 운영자 간
 * 전달은 콘솔 밖에서 이루어진다.
 *
 * 승인은 제안자 본인이 할 수 없다. 그 판정은 서버가 하며, 화면은 거절 사유를
 * 그대로 보여 준다.
 */
export function MemberSanctionApprovalPage() {
  const { apiClient, capabilities, currentOperator } =
    usePlatformOperatorAuth()

  const [accountType, setAccountType] = useState<AccountType>('CONSUMER')
  const [accountId, setAccountId] = useState('')
  const [sanctionId, setSanctionId] = useState('')
  const [sanctionVersion, setSanctionVersion] = useState('')
  const [reasonCode, setReasonCode] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [awaitingReauthentication, setAwaitingReauthentication] =
    useState(false)
  const {
    attempt,
    beginAttempt,
    clearAttempt,
    isAttemptCurrent,
  } = useLogicalCommandAttempt(
    () => ({ idempotencyKey: createIdempotencyKey() }),
    JSON.stringify({
      accountType,
      accountId,
      sanctionId,
      sanctionVersion,
      reasonCode,
    }),
  )

  const decision = decideCapability(
    capabilities,
    'ACCOUNT_PERMANENT_SANCTION_APPROVE',
  )

  const isSuperAdmin = currentOperator?.roles.includes('SUPER_ADMIN') === true

  if (decision === 'denied' || (decision === 'allowed' && !isSuperAdmin)) {
    return (
      <Alert tone="warning" title="이 업무를 수행할 권한이 없습니다.">
        영구 정지 추가 승인에는{' '}
        <code>SUPER_ADMIN</code> 역할과{' '}
        <code>ACCOUNT_PERMANENT_SANCTION_APPROVE</code> 권한이 모두 필요합니다.
      </Alert>
    )
  }

  function handleRequestReauthentication(event: React.FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (accountId.trim().length === 0) {
      next.accountId = '대상 계정 ID를 입력해 주세요.'
    }
    if (sanctionId.trim().length === 0) {
      next.sanctionId = '제재 ID를 입력해 주세요.'
    }
    const parsed = Number(sanctionVersion)
    if (
      sanctionVersion.trim().length === 0 ||
      !Number.isInteger(parsed) ||
      parsed < 0
    ) {
      next.sanctionVersion = '제재 version을 숫자로 입력해 주세요.'
    }
    if (reasonCode.trim().length === 0) {
      next.reasonCode = '승인 사유 코드를 입력해 주세요.'
    } else if (!REASON_CODE_PATTERN.test(reasonCode)) {
      next.reasonCode = '대문자·숫자·밑줄만 사용할 수 있습니다.'
    }
    setErrors(next)
    setFormError(null)
    setResult(null)
    if (Object.keys(next).length > 0) {
      return
    }
    beginAttempt()
    setAwaitingReauthentication(true)
  }

  async function handleApproved(approval: string) {
    if (attempt === null || !isAttemptCurrent()) {
      setAwaitingReauthentication(false)
      clearAttempt()
      setFormError(
        '재인증 중 명령 입력이 변경됐습니다. 변경된 내용으로 다시 제출해 주세요.',
      )
      return
    }
    setAwaitingReauthentication(false)
    setSubmitting(true)
    setFormError(null)
    try {
      const sanction = await approvePermanentSanction(apiClient, {
        sanctionId: sanctionId.trim(),
        version: Number(sanctionVersion),
        reauthenticationApproval: approval,
        idempotencyKey: attempt.idempotencyKey,
        body: { decision: 'APPROVE', reasonCode: reasonCode.trim() },
      })
      // 서버가 준 status를 그대로 전한다. 화면이 "적용됨"으로 단정하지 않는다.
      setResult(
        `제재 ${sanction.sanctionId}가 ${sanction.status} 상태가 됐습니다.`,
      )
      clearAttempt()
      setAccountType('CONSUMER')
      setAccountId('')
      setSanctionId('')
      setSanctionVersion('')
      setReasonCode('')
    } catch (error) {
      setFormError(approvalErrorMessage(error))
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
            제안자에게 전달받은 대상 계정 정보와 제재 ID·version으로
            승인합니다.
          </p>
        </div>
      </header>

      <Alert tone="info" title="승인 대기 목록은 제공되지 않습니다.">
        승인 대기 제재를 조회하는 API가 아직 없습니다. 제안한 운영자가 전달한
        대상 계정 유형·계정 ID·제재 ID·version을 입력해 주세요.{' '}
        <strong>제안자 본인은 승인할 수 없습니다.</strong>
      </Alert>

      {result !== null && <Alert tone="info" title={result} />}
      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="po-form"
        onSubmit={handleRequestReauthentication}
        aria-label="영구 정지 추가 승인"
        inert={awaitingReauthentication}
        noValidate
      >
        <SelectField
          label="대상 계정 유형"
          value={accountType}
          onChange={(event) => setAccountType(event.target.value as AccountType)}
        >
          <option value="CONSUMER">일반 사용자</option>
          <option value="STORE_OPERATOR">매장 운영자</option>
        </SelectField>

        <TextField
          label="대상 계정 ID"
          name="accountId"
          value={accountId}
          error={errors.accountId ?? null}
          onChange={(event) => setAccountId(event.target.value)}
        />

        <TextField
          label="제재 ID"
          name="sanctionId"
          value={sanctionId}
          error={errors.sanctionId ?? null}
          onChange={(event) => setSanctionId(event.target.value)}
        />

        <TextField
          label="제재 version"
          name="sanctionVersion"
          inputMode="numeric"
          value={sanctionVersion}
          error={errors.sanctionVersion ?? null}
          onChange={(event) => setSanctionVersion(event.target.value)}
        />

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

      {awaitingReauthentication && attempt !== null && (
        <ReauthenticationDialog
          purpose="PERMANENT_ACCOUNT_SANCTION_APPROVAL"
          targetType={accountTargetType(accountType)}
          targetId={accountId.trim()}
          description={`제재 ${sanctionId.trim()}를 승인합니다. 본인 확인이 필요합니다.`}
          onApproved={handleApproved}
          onCancel={() => setAwaitingReauthentication(false)}
        />
      )}
    </section>
  )
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
    return '해당 제재를 찾을 수 없습니다. 제재 ID를 다시 확인해 주세요.'
  }
  switch (error.code) {
    case AuthErrorCode.PERMANENT_SANCTION_APPROVAL_CONFLICT:
      return '이미 승인됐거나 종결된 제안입니다. 최신 상태를 확인해 주세요.'
    case AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT:
      return '제재 상태가 변경됐습니다. 최신 version을 확인한 뒤 다시 시도해 주세요.'
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '같은 키로 다른 내용을 보냈습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.'
    default:
      return error.message
  }
}
