import { PLATFORM_OPERATOR_PATHS } from '../../../../../app/routes/paths/platformOperatorPaths'
import { useEffect, useState } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { isApiError, isNetworkError } from '../../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../../shared/api/envelope'
import { Button } from '../../../../../shared/ui/Button'
import { PasswordField } from '../../../../../shared/ui/Field'
import { Alert, Loading } from '../../../../../shared/ui/Feedback'
import { AuthErrorCode } from '../../../../../shared/auth/authErrors'
import { usePlatformOperatorAuth } from '../../../../../app/shells/platform-operator/PlatformOperatorAuthProvider'
import './platform-operator-auth.css'

/**
 * 최초 임시 비밀번호 변경.
 *
 * 발급받은 임시 비밀번호로 처음 로그인한 운영자만 지나가는 화면이다.
 * 성공하면 서버가 새 토큰을 주므로 다시 로그인하지 않는다.
 *
 * 새 비밀번호 정책(8~64자, 대문자·소문자·숫자·특수문자 중 3종 이상)은 서버가
 * 판정한다. 화면은 두 입력이 서로 다른 것처럼 확실히 틀린 경우만 미리 막고,
 * 나머지는 서버 응답을 그대로 보여 준다. 정책을 프론트에 복제하면 서버가
 * 바뀌었을 때 화면이 멀쩡한 비밀번호를 거절한다.
 */
export function PlatformOperatorInitialPasswordPage() {
  const { status, changeInitialPassword } = usePlatformOperatorAuth()
  const navigate = useNavigate()

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [newPasswordConfirm, setNewPasswordConfirm] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // 변경이 끝나 정상 세션이 되면 업무 화면으로 보낸다.
  useEffect(() => {
    if (status === 'authenticated') {
      void navigate(PLATFORM_OPERATOR_PATHS.members, { replace: true })
    }
  }, [status, navigate])

  if (status === 'restoring') {
    return <Loading label="운영자 세션을 확인하는 중입니다." />
  }

  // 제한 세션이 아닌데 이 주소로 들어온 경우다. 폼을 보여 줄 이유가 없다.
  if (status === 'unauthenticated') {
    return <Navigate to={PLATFORM_OPERATOR_PATHS.signIn} replace />
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    const errors: Record<string, string> = {}
    if (currentPassword.length === 0) {
      errors.currentPassword = '현재 임시 비밀번호를 입력해 주세요.'
    }
    if (newPassword.length === 0) {
      errors.newPassword = '새 비밀번호를 입력해 주세요.'
    }
    // 서버도 확인 불일치를 거절하지만, 이건 사용자가 오타를 낸 것이 확실하므로
    // 왕복 없이 바로 알린다.
    if (newPassword.length > 0 && newPassword !== newPasswordConfirm) {
      errors.newPasswordConfirm = '새 비밀번호가 서로 다릅니다.'
    }
    if (newPassword.length > 0 && newPassword === currentPassword) {
      errors.newPassword = '임시 비밀번호와 다른 비밀번호를 사용해 주세요.'
    }

    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) {
      return
    }

    setSubmitting(true)
    setFormError(null)
    try {
      await changeInitialPassword({
        currentPassword,
        newPassword,
        newPasswordConfirm,
      })
    } catch (error) {
      setFormError(initialPasswordErrorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="po-auth">
      <section className="po-auth__card" aria-labelledby="po-initial-heading">
        <div className="po-auth__brand" aria-hidden="true">
          <span className="po-auth__brand-mark">MiriYum</span>
          <span className="po-auth__brand-suffix">HQ</span>
        </div>
        <h1 className="po-auth__title" id="po-initial-heading">
          최초 비밀번호 변경
        </h1>
        <p className="po-auth__subtitle">
          발급받은 임시 비밀번호를 새 비밀번호로 바꿔야 콘솔을 사용할 수 있습니다.
        </p>

        <form
          className="po-auth__form"
          onSubmit={handleSubmit}
          aria-label="최초 비밀번호 변경"
          noValidate
        >
          <PasswordField
            label="현재 임시 비밀번호"
            name="currentPassword"
            autoComplete="current-password"
            value={currentPassword}
            error={fieldErrors.currentPassword ?? null}
            onChange={(event) => setCurrentPassword(event.target.value)}
          />

          <PasswordField
            label="새 비밀번호"
            name="newPassword"
            autoComplete="new-password"
            help="8~64자이며 대문자·소문자·숫자·특수문자 중 3종 이상을 포함합니다."
            value={newPassword}
            error={fieldErrors.newPassword ?? null}
            onChange={(event) => setNewPassword(event.target.value)}
          />

          <PasswordField
            label="새 비밀번호 확인"
            name="newPasswordConfirm"
            autoComplete="new-password"
            value={newPasswordConfirm}
            error={fieldErrors.newPasswordConfirm ?? null}
            onChange={(event) => setNewPasswordConfirm(event.target.value)}
          />

          {formError !== null && <Alert tone="error" title={formError} />}

          <Button
            type="submit"
            variant="primary"
            size="lg"
            block
            loading={submitting}
          >
            비밀번호 변경
          </Button>
        </form>
      </section>
    </main>
  )
}

function initialPasswordErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (!isApiError(error)) {
    return '비밀번호를 변경하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case AuthErrorCode.INVALID_CREDENTIALS:
      return '현재 임시 비밀번호가 올바르지 않습니다.'
    case AuthErrorCode.ACCOUNT_RESTRICTED:
      return '중지된 계정입니다. 슈퍼관리자에게 문의해 주세요.'
    case CommonErrorCode.CONCURRENT_MODIFICATION:
      // 다른 요청이 먼저 확정됐다. 새 비밀번호가 무엇인지 알 수 없으므로
      // 바뀌었다고 단정하지 않고 다시 확인하게 한다.
      return '다른 변경 요청이 먼저 처리됐습니다. 다시 로그인해 확인해 주세요.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '시도 횟수가 많습니다. 잠시 후 다시 시도해 주세요.'
    default:
      // 비밀번호 정책 위반은 서버 문구가 가장 정확하다.
      return error.message
  }
}
