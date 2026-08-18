import { useState } from 'react'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { usePlatformOperatorAuth } from '../../platform-operator-auth'
import {
  suspendOperatorAccount,
  type OperatorAccountDetail,
} from '../api/operatorAccountApi'
import {
  EMPTY_COMMAND_FORM,
  OperatorCommandFields,
  validateCommandFields,
  type OperatorCommandFormState,
} from './OperatorCommandFields'
import { ReauthenticationDialog } from './ReauthenticationDialog'
import './page.css'

/**
 * 운영자 계정 중지.
 *
 * 되돌리는 계약이 없다. 계정 재활성화는 `#282` 명세가 명시적으로 제외했으므로
 * 화면이 "해제" 버튼을 만들지 않고, 중지가 되돌릴 수 없는 조치임을 알린다.
 *
 * 확인 절차를 두 단계로 둔다. 표시명을 그대로 입력해야 버튼이 열리고, 그 뒤
 * 재인증을 통과해야 전송된다. 목록에서 한 번의 클릭으로 확정되지 않게 한다는
 * 콘솔 공통 규칙과 같은 이유다.
 */
export function OperatorSuspensionForm({
  account,
  onSuspended,
}: {
  account: OperatorAccountDetail
  onSuspended: () => void
}) {
  const { apiClient } = usePlatformOperatorAuth()
  const [confirmation, setConfirmation] = useState('')
  const [command, setCommand] =
    useState<OperatorCommandFormState>(EMPTY_COMMAND_FORM)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [awaitingReauthentication, setAwaitingReauthentication] =
    useState(false)
  const [idempotencyKey, setIdempotencyKey] = useState(() =>
    createIdempotencyKey(),
  )

  const confirmed = confirmation.trim() === account.displayName

  if (account.status === 'SUSPENDED') {
    return (
      <section aria-labelledby="suspension-heading">
        <h2 className="po-section__title" id="suspension-heading">
          계정 중지
        </h2>
        <Alert tone="info" title="이미 중지된 계정입니다.">
          중지 해제는 이 콘솔에서 제공하지 않습니다. 계정 재활성화 계약이
          없습니다.
        </Alert>
      </section>
    )
  }

  function handleRequestReauthentication(event: React.FormEvent) {
    event.preventDefault()
    const nextErrors = validateCommandFields(command)
    if (!confirmed) {
      nextErrors.confirmation = '표시명을 정확히 입력해 주세요.'
    }
    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0) {
      return
    }
    setAwaitingReauthentication(true)
  }

  async function handleApproved(approval: string) {
    setAwaitingReauthentication(false)
    setSubmitting(true)
    setFormError(null)
    try {
      await suspendOperatorAccount(apiClient, {
        operatorId: account.operatorId,
        context: {
          caseId: command.caseId.trim(),
          caseVersion: Number(command.caseVersion),
        },
        reauthenticationApproval: approval,
        idempotencyKey,
        body: { reason: command.reason },
      })
      setIdempotencyKey(createIdempotencyKey())
      setConfirmation('')
    } catch (error) {
      setFormError(suspensionErrorMessage(error))
    } finally {
      setSubmitting(false)
      // 결과를 화면이 단정하지 않는다. 서버가 준 상태를 다시 읽는다.
      onSuspended()
    }
  }

  return (
    <section aria-labelledby="suspension-heading">
      <h2 className="po-section__title" id="suspension-heading">
        계정 중지
      </h2>

      <Alert tone="error" title="되돌릴 수 없는 조치입니다.">
        중지하면 해당 운영자는 콘솔에 접근할 수 없습니다. 이 콘솔에는 중지 해제
        기능이 없습니다. 처리에는 본인 확인이 필요하며 감사 기록에 남습니다.
      </Alert>

      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="po-form"
        onSubmit={handleRequestReauthentication}
        aria-label="계정 중지"
        noValidate
      >
        <TextField
          label="확인을 위해 표시명 입력"
          name="confirmation"
          help={`중지하려면 "${account.displayName}"을 그대로 입력해 주세요.`}
          value={confirmation}
          error={errors.confirmation ?? null}
          onChange={(event) => setConfirmation(event.target.value)}
        />

        <OperatorCommandFields
          state={command}
          errors={errors}
          onChange={setCommand}
        />

        <Button
          type="submit"
          variant="primary"
          size="lg"
          loading={submitting}
          disabled={!confirmed || awaitingReauthentication}
        >
          계정 중지
        </Button>
      </form>

      {awaitingReauthentication && (
        <ReauthenticationDialog
          purpose="OPERATOR_SUSPENSION"
          targetType="PLATFORM_OPERATOR_ACCOUNT"
          targetId={account.operatorId}
          description={`${account.displayName} 계정을 중지합니다. 본인 확인이 필요합니다.`}
          onApproved={handleApproved}
          onCancel={() => setAwaitingReauthentication(false)}
        />
      )}
    </section>
  )
}

function suspensionErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 처리 여부를 상태에서 다시 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '계정을 중지하지 못했습니다.'
  }
  if (error.status === 403) {
    return '이 작업을 수행할 권한이 없거나 전제조건을 충족하지 않습니다.'
  }
  switch (error.code) {
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '같은 키로 다른 내용을 보냈습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.'
    case CommonErrorCode.CONCURRENT_MODIFICATION:
      return '대상 상태가 변경됐습니다. 최신 정보를 확인한 뒤 다시 시도해 주세요.'
    default:
      return error.message
  }
}
