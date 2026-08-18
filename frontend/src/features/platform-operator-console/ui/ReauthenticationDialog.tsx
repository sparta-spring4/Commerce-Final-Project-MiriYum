import { useState } from 'react'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { Button } from '../../../shared/ui/Button'
import { PasswordField } from '../../../shared/ui/Field'
import { AuthErrorCode } from '../../auth/model/authErrors'
import { useDialogFocus } from '../../../shared/ui/useDialogFocus'
import { usePlatformOperatorAuth } from '../../platform-operator-auth'
import {
  createReauthenticationApproval,
  type AdminCommandPurpose,
  type AdminTargetType,
} from '../api/reauthenticationApi'
import './page.css'

/**
 * 고위험 명령 전 재인증.
 *
 * 승인값은 목적·대상·현재 세션에 결속된 5분 일회용이다. 그래서 이 컴포넌트는
 * 값을 보관하지 않고 부모에게 넘긴 뒤 폼을 비운다. 모듈이나 storage에 캐시하면
 * 다음 명령이 이미 소비된 승인을 보내 실패한다.
 *
 * 입력받은 비밀번호도 승인 발급 직후 비운다. state에 남겨 두면 명령이 끝날
 * 때까지 메모리에 평문이 머문다.
 *
 * "확인" 한 번으로 명령까지 실행하지 않는다. 승인 발급과 명령 실행을 나눠,
 * 부모가 사유·대상·version을 다시 확인한 뒤 보내게 한다.
 */
export function ReauthenticationDialog({
  purpose,
  targetType,
  targetId,
  description,
  onApproved,
  onCancel,
}: {
  purpose: AdminCommandPurpose
  targetType: AdminTargetType
  targetId: string
  description: string
  onApproved: (approval: string) => void
  onCancel: () => void
}) {
  const { apiClient } = usePlatformOperatorAuth()
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  /*
   * 처리 중에는 ESC로 닫지 않는다. 승인 발급이 떠 있는 동안 닫으면 발급된
   * 승인값을 아무도 받지 못한 채 사라지고, 사용자는 취소된 줄 안다.
   */
  const dialogRef = useDialogFocus<HTMLDivElement>({
    onEscape: () => {
      setPassword('')
      onCancel()
    },
    escapeEnabled: !submitting,
  })

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (password.length === 0) {
      setError('현재 비밀번호를 입력해 주세요.')
      return
    }

    setSubmitting(true)
    setError(null)
    try {
      const approval = await createReauthenticationApproval(apiClient, {
        currentPassword: password,
        purpose,
        targetType,
        targetId,
      })
      // 평문 비밀번호를 먼저 비운다. 부모 콜백이 던져도 남지 않는다.
      setPassword('')
      onApproved(approval.approval)
    } catch (caught) {
      setError(reauthenticationErrorMessage(caught))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div
      ref={dialogRef}
      className="po-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="po-reauth-heading"
      aria-describedby="po-reauth-description"
      tabIndex={-1}
    >
      <h2 className="po-dialog__title" id="po-reauth-heading">
        재인증이 필요합니다
      </h2>
      <p className="po-dialog__description" id="po-reauth-description">
        {description}
      </p>

      <form
        className="po-dialog__form"
        onSubmit={handleSubmit}
        aria-label="재인증"
        noValidate
      >
        <PasswordField
          label="현재 비밀번호"
          name="currentPassword"
          autoComplete="current-password"
          value={password}
          error={error}
          onChange={(event) => setPassword(event.target.value)}
        />

        <p className="po-dialog__notice">
          승인은 이 작업 한 번에만 사용되며 잠시 뒤 만료됩니다.
        </p>

        <div className="po-dialog__actions">
          <Button
            type="button"
            variant="ghost"
            disabled={submitting}
            onClick={() => {
              setPassword('')
              onCancel()
            }}
          >
            취소
          </Button>
          {/* 중복 제출 방지. 승인은 일회용이라 두 번 발급되면 하나는 버려진다. */}
          <Button
            type="submit"
            variant="primary"
            loading={submitting}
            disabled={submitting}
          >
            확인
          </Button>
        </div>
      </form>
    </div>
  )
}

function reauthenticationErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (!isApiError(error)) {
    return '재인증에 실패했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case AuthErrorCode.INVALID_CREDENTIALS:
      return '비밀번호가 올바르지 않습니다.'
    case AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID:
      return '세션이 만료됐습니다. 다시 로그인해 주세요.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '시도 횟수가 많습니다. 잠시 후 다시 시도해 주세요.'
    default:
      // 권한·전제조건 거부(403 ADMIN_001)는 서버 문구가 가장 정확하다.
      return error.message
  }
}
