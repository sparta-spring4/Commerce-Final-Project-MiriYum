import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { ROUTES } from '../../../app/routes'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { Alert } from '../../../shared/ui/Feedback'
import { createConsumerKakaoSession } from '../api/consumerAuthApi'
import { useConsumerAuth } from '../ConsumerAuthProvider'
import { kakaoRedirectUri } from '../model/kakaoOAuth'

/** 카카오 인가 완료 뒤 code·state를 Auth API 세션 교환으로 전달한다. */
export function ConsumerKakaoCallbackPage() {
  const { completeKakaoSignIn } = useConsumerAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const started = useRef(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (started.current) {
      return
    }
    started.current = true

    const parameters = new URLSearchParams(location.search)
    const authorizationCode = parameters.get('code')
    const state = parameters.get('state')
    if (authorizationCode === null || state === null) {
      setError('카카오 로그인 정보를 확인하지 못했습니다. 다시 시도해 주세요.')
      return
    }

    void createConsumerKakaoSession({
      authorizationCode,
      state,
      redirectUri: kakaoRedirectUri(),
    })
      .then((result) => {
        if (result.status === 'AUTHENTICATED' && result.accessToken !== undefined) {
          completeKakaoSignIn(result.accessToken)
          void navigate(ROUTES.home, { replace: true })
          return
        }
        if (result.status === 'SIGN_UP_REQUIRED' && result.signUpTicket !== undefined) {
          // 가입 티켓은 5분 수명의 bearer 값이다. URL·브라우저 기록에 넣지 않는다.
          void navigate(ROUTES.consumerKakaoSignUp, {
            replace: true,
            state: { signUpTicket: result.signUpTicket },
          })
          return
        }
        setError('카카오 로그인 결과를 처리하지 못했습니다. 다시 시도해 주세요.')
      })
      .catch((caught: unknown) => setError(callbackErrorMessage(caught)))
  }, [completeKakaoSignIn, location.search, navigate])

  return (
    <div className="auth-page">
      <div className="mi-ambient" aria-hidden="true" />
      <main className="auth-card mi-ambient-content" aria-live="polite">
        <header className="auth-card__header">
          <p className="auth-card__mark" aria-hidden="true">
            MiriYum
          </p>
          <h1>카카오 로그인</h1>
          <p className="auth-card__lead">로그인 정보를 확인하고 있습니다.</p>
        </header>
        {error === null ? (
          <p className="auth-card__lead">잠시만 기다려 주세요.</p>
        ) : (
          <>
            <Alert tone="error" title={error} />
            <p className="auth-card__switch">
              <Link className="auth-card__switch-link" to={ROUTES.consumerSignIn}>
                로그인 화면으로 돌아가기
              </Link>
            </p>
          </>
        )}
      </main>
    </div>
  )
}

function callbackErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (isApiError(error) && error.status === 400) {
    return '카카오 로그인 시간이 만료되었거나 요청이 올바르지 않습니다. 다시 시도해 주세요.'
  }
  return '카카오 로그인을 완료하지 못했습니다. 다시 시도해 주세요.'
}
