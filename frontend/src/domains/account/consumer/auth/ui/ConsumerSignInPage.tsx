import { PUBLIC_PATHS } from '../../../../../app/routes/paths/publicPaths'
import { CONSUMER_PATHS } from '../../../../../app/routes/paths/consumerPaths'
import { STORE_OPERATOR_PATHS } from '../../../../../app/routes/paths/storeOperatorPaths'
import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { isApiError, isNetworkError } from '../../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../../shared/api/envelope'
import { readReturnTo } from '../../../../../app/returnTo'
import { Button } from '../../../../../shared/ui/Button'
import { PasswordField, TextField } from '../../../../../shared/ui/Field'
import { Alert } from '../../../../../shared/ui/Feedback'
import { Icon } from '../../../../../shared/ui/Icon'
import { createConsumerKakaoAuthorization } from '../api/consumerAuthApi'
import { useConsumerAuth } from '../../../../../app/shells/consumer/ConsumerAuthProvider'
import { AuthErrorCode } from '../../../../../shared/auth/authErrors'
import { browserRedirect, kakaoRedirectUri } from '../model/kakaoOAuth'
import { collectErrors, validateEmail } from '../../../../../shared/auth/validation'

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
  // 비밀번호 폼과 별개의 수단이므로 오류도 따로 둔다. 한 칸을 공유하면
  // 어느 쪽을 고치라는 안내인지 사용자가 알 수 없다.
  const [kakaoError, setKakaoError] = useState<string | null>(null)
  const [startingKakao, setStartingKakao] = useState(false)

  const destination = readReturnTo(location.search) ?? PUBLIC_PATHS.home

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

  /**
   * 카카오 인가 화면으로 나간다.
   *
   * 이메일 폼 검증을 거치지 않는다. 카카오 로그인에는 이 화면의 입력이 쓰이지 않는다.
   */
  async function handleKakaoSignIn() {
    setStartingKakao(true)
    setKakaoError(null)
    try {
      const authorizationUrl = await createConsumerKakaoAuthorization({
        redirectUri: kakaoRedirectUri(),
      })
      browserRedirect.assign(authorizationUrl)
      // 이동이 시작됐으므로 진행 상태를 되돌리지 않는다. 되돌리면 화면이 사라지기
      // 전에 다시 눌러 인가 요청이 두 번 나가고 state 쿠키가 덮어써진다.
    } catch (error) {
      setKakaoError(kakaoErrorMessage(error))
      setStartingKakao(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="mi-ambient" aria-hidden="true" />

      <div className="auth-card mi-ambient-content">
        <header className="auth-card__header">
          {/*
            시안은 여기에 로고 이미지를 둔다. ZIP은 로고를 원격 주소로만
            참조하고 파일을 담고 있지 않다. 외부 호스트를 부르지 않기로 한
            theme.css의 결정을 따라 같은 자리에 브랜드 워드마크를 둔다.
          */}
          <p className="auth-card__mark" aria-hidden="true">
            MiriYum
          </p>
          <h1>로그인</h1>
          <p className="auth-card__lead">맛있는 여정의 시작</p>
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
          <TextField
            label="이메일"
            type="email"
            name="email"
            autoComplete="email"
            placeholder="example@miriyum.com"
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
            로그인
          </Button>
        </form>

        {/*
          form 밖에 둔다. 안에 두면 카카오 버튼이 폼의 제출 대상으로 묶여
          Enter 키 동작과 검증 흐름이 두 수단에 섞인다.
        */}
        <section className="auth-social" aria-label="다른 방법으로 로그인">
          <p className="auth-social__divider">
            <span>또는</span>
          </p>

          {kakaoError !== null && <Alert tone="error" title={kakaoError} />}

          <Button
            variant="ghost"
            block
            className="auth-social__kakao"
            loading={startingKakao}
            onClick={() => {
              void handleKakaoSignIn()
            }}
          >
            카카오 로그인
          </Button>
        </section>

        <footer className="auth-card__foot">
          <p className="auth-card__switch">
            아직 계정이 없으신가요?{' '}
            <Link className="auth-card__switch-link" to={CONSUMER_PATHS.signUp}>
              회원가입
            </Link>
          </p>
          {/* 매장 운영자는 별도 shell이다. 같은 폼에서 역할을 고르게 하지 않는다. */}
          <Link className="auth-card__aside" to={STORE_OPERATOR_PATHS.signIn}>
            <Icon name="store" className="mi-icon--sm" />
            식당 대표자 로그인
          </Link>
        </footer>

        {/*
          시안의 진행 중 오버레이. 카드 위를 덮어 두 번 제출되는 것을 눈으로도
          막는다. 상태 자체는 버튼의 aria-busy가 이미 알리므로 여기서는 다시
          읽히지 않게 숨긴다.
        */}
        {submitting && (
          <div className="auth-card__overlay" aria-hidden="true">
            <span className="mi-spinner auth-card__overlay-spinner" />
            <p>로그인 중...</p>
          </div>
        )}
      </div>
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

/**
 * 인가 주소 발급 실패를 안내한다.
 *
 * `COMMON_012`는 일시 장애만이 아니다. 서버에서 카카오 연동을 끄거나 콜백 주소가
 * 허용 목록에 없을 때도 같은 코드가 온다. 두 경우 모두 사용자가 기다린다고
 * 풀리지 않으므로 "잠시 후 다시"가 아니라 다른 수단을 안내한다.
 */
function kakaoErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (!isApiError(error)) {
    return '카카오 로그인을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case CommonErrorCode.SERVICE_UNAVAILABLE:
      return '카카오 로그인을 지금 이용할 수 없습니다. 다른 방법으로 로그인해 주세요.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    default:
      return '카카오 로그인을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
