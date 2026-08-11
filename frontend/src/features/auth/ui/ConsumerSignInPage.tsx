import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { ROUTES } from '../../../app/routes'
import { readReturnTo } from '../../../app/returnTo'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { useConsumerAuth } from '../ConsumerAuthProvider'
import { AuthErrorCode } from '../model/authErrors'
import { collectErrors, validateEmail } from '../model/validation'

/**
 * 일반 사용자 로그인.
 *
 * 로그인 성공 후에는 보존한 목적지로 돌아간다. 목적지는 같은 오리진의
 * 절대 경로만 허용한다(returnTo가 판정).
 */
export function ConsumerSignInPage() {
  const { status, signIn } = useConsumerAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const destination = readReturnTo(location.search) ?? ROUTES.home

  // 이미 로그인한 상태로 로그인 화면에 오면 목적지로 보낸다.
  useEffect(() => {
    if (status === 'authenticated') {
      void navigate(destination, { replace: true })
    }
  }, [status, destination, navigate])

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    // 비밀번호는 제출 전 형식 검증을 하지 않는다. 기존 계정의 비밀번호가
    // 현재 정책보다 약할 수 있고, 로그인 화면에서 정책을 노출할 이유도 없다.
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
    <div className="mi-container auth-page">
      <header className="auth-page__header">
        <h1>로그인</h1>
        <p>맛있는 여정의 시작</p>
      </header>

      {/*
        noValidate로 브라우저 기본 검증을 끈다. 기본 검증이 먼저 걸리면 우리가
        입력에 연결한 접근 가능한 오류 문구 대신 브라우저 말풍선이 뜨고,
        문구가 브라우저·언어별로 달라진다.
      */}
      <form
        className="auth-form"
        onSubmit={handleSubmit}
        aria-label="로그인"
        noValidate
      >
        {formError !== null && (
          <Alert tone="error" title={formError} />
        )}

        <TextField
          label="이메일"
          type="email"
          name="email"
          autoComplete="email"
          value={email}
          error={fieldErrors.email ?? null}
          onChange={(event) => setEmail(event.target.value)}
        />

        <TextField
          label="비밀번호"
          type="password"
          name="password"
          autoComplete="current-password"
          value={password}
          error={fieldErrors.password ?? null}
          onChange={(event) => setPassword(event.target.value)}
        />

        <Button type="submit" variant="primary" block loading={submitting}>
          로그인
        </Button>
      </form>

      <p className="auth-page__switch">
        아직 계정이 없으신가요? <Link to={ROUTES.consumerSignUp}>회원가입</Link>
      </p>
      <p className="auth-page__switch">
        {/* 매장 운영자는 별도 shell이다. 같은 폼에서 역할을 고르게 하지 않는다. */}
        <Link to={ROUTES.storeOperatorSignIn}>식당 대표자 로그인</Link>
      </p>
    </div>
  )
}

/**
 * 서버 code로 분기한다.
 *
 * `AUTH_005`는 계정 존재 여부를 나누지 않는다. "없는 이메일"과 "틀린 비밀번호"를
 * 구분해 표시하면 가입 여부를 확인하는 통로가 된다.
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
      return '이메일 또는 비밀번호를 확인해 주세요.'
    case AuthErrorCode.ACCOUNT_RESTRICTED:
      return '현재 계정 상태로는 로그인할 수 없습니다. 고객센터에 문의해 주세요.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.'
    case CommonErrorCode.VALIDATION_FAILED:
      return '입력한 내용을 다시 확인해 주세요.'
    case CommonErrorCode.SERVICE_UNAVAILABLE:
      return '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
    default:
      return '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
