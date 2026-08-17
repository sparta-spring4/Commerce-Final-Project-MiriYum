import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { ROUTES } from '../../../app/routes'
import { readReturnTo } from '../../../app/returnTo'
import { Button } from '../../../shared/ui/Button'
import { PasswordField, TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { Icon } from '../../../shared/ui/Icon'
import { AuthErrorCode } from '../../auth/model/authErrors'
import { collectErrors, validateEmail } from '../../auth/model/validation'
import { usePlatformOperatorAuth } from '../PlatformOperatorAuthProvider'
import './platform-operator-auth.css'

/**
 * 플랫폼 운영자 로그인.
 *
 * 공개 회원가입이 없다. 계정은 슈퍼관리자가 발급하므로 가입 링크를 두지 않는다.
 * 시안에는 "15분간 활동이 없으면 세션이 만료됩니다"라는 문구가 있지만 그 수치를
 * 화면에 박지 않는다. 계약이 과거 세션 정책 수치를 선반영하지 말라고 못박았고,
 * 실제 값은 로그인 응답의 `idleExpiresAt`·`absoluteExpiresAt`으로 온다.
 */
export function PlatformOperatorSignInPage() {
  const { status, signIn } = usePlatformOperatorAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const destination = readReturnTo(location.search) ?? ROUTES.platformOperatorMembers

  useEffect(() => {
    if (status === 'authenticated') {
      void navigate(destination, { replace: true })
      return
    }
    // 임시 비밀번호로 들어온 세션은 업무 화면으로 보내지 않는다.
    if (status === 'restricted') {
      void navigate(ROUTES.platformOperatorInitialPassword, { replace: true })
    }
  }, [status, destination, navigate])

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    const errors = collectErrors({ email: validateEmail(email) })
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) {
      return
    }
    if (password.length === 0) {
      setFieldErrors({ password: '비밀번호를 입력해 주세요.' })
      return
    }

    setSubmitting(true)
    setFormError(null)
    try {
      await signIn({ email, password })
    } catch (error) {
      setFormError(signInErrorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="po-auth">
      <section className="po-auth__card" aria-labelledby="po-signin-heading">
        <div className="po-auth__brand" aria-hidden="true">
          <span className="po-auth__brand-mark">MiriYum</span>
          <span className="po-auth__brand-suffix">HQ</span>
        </div>
        <h1 className="po-auth__title" id="po-signin-heading">
          운영자 로그인
        </h1>
        <p className="po-auth__subtitle">인가된 담당자만 접근할 수 있습니다.</p>

        <form
          className="po-auth__form"
          onSubmit={handleSubmit}
          aria-label="운영자 로그인"
          noValidate
        >
          <TextField
            label="운영자 이메일"
            type="email"
            name="email"
            autoComplete="email"
            placeholder="operator@company.com"
            leadingIcon={<Icon name="mail" />}
            value={email}
            error={fieldErrors.email ?? null}
            onChange={(event) => setEmail(event.target.value)}
          />

          <PasswordField
            label="비밀번호"
            name="password"
            autoComplete="current-password"
            value={password}
            error={fieldErrors.password ?? null}
            onChange={(event) => setPassword(event.target.value)}
          />

          {formError !== null && <Alert tone="error" title={formError} />}

          <Button
            type="submit"
            variant="primary"
            size="lg"
            block
            loading={submitting}
          >
            인증
          </Button>
        </form>

        <p className="po-auth__notice">
          모든 인증 시도는 기록되며, 계정은 슈퍼관리자가 발급합니다.
        </p>
      </section>
    </main>
  )
}

/**
 * 로그인 실패 사유.
 *
 * 존재하지 않는 계정과 비밀번호 오류를 구분해 알리지 않는다. 서버도 두 경우를
 * 같은 `AUTH_005`로 답하며, 화면이 갈라 주면 운영자 계정 목록을 추측할 수 있다.
 */
function signInErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (!isApiError(error)) {
    return '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case AuthErrorCode.INVALID_CREDENTIALS:
      return '이메일 또는 비밀번호가 올바르지 않습니다.'
    case AuthErrorCode.ACCOUNT_RESTRICTED:
      return '중지된 계정입니다. 슈퍼관리자에게 문의해 주세요.'
    case AuthErrorCode.ORIGIN_REJECTED:
      return '허용되지 않은 접속 경로입니다.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '시도 횟수가 많습니다. 잠시 후 다시 시도해 주세요.'
    default:
      return error.message
  }
}
