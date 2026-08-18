import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { ROUTES } from '../../../app/routes'
import { readReturnTo } from '../../../app/returnTo'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { AuthErrorCode } from '../../auth/model/authErrors'
import { collectErrors, validateEmail } from '../../auth/model/validation'
import { useStoreOperatorAuth } from '../StoreOperatorAuthProvider'
import { OperatorIcon } from './OperatorIcon'

/**
 * 식당 대표자 로그인.
 *
 * 로그인 성공 후 목적지는 보존한 `returnTo`이며, 없으면 운영 홈이다.
 * 소유 매장 목록 조회 계약이 없으므로 "매장 있음/없음"을 추측해 자동 분기하지
 * 않는다. 운영 홈이 아는 매장이 있으면 관리 화면으로, 없으면 등록 안내를 보여 준다.
 */
export function StoreOperatorSignInPage() {
  const { status, signIn, signOut, signOutPending } = useStoreOperatorAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [retryingSignOut, setRetryingSignOut] = useState(false)

  const destination = readReturnTo(location.search) ?? ROUTES.storeOperatorHome

  useEffect(() => {
    if (status === 'authenticated') {
      void navigate(destination, { replace: true })
    }
  }, [status, destination, navigate])

  /*
   * 세션 복구가 끝나기 전에는 로그인을 보내지 않는다.
   *
   * 새로고침 직후 shell은 항상 재발급을 시도한다. 그 사이에 로그인이 함께 나가면
   * 두 응답이 도착 순서와 무관하게 같은 토큰 자리에 쓰이고, 늦게 도착한 재발급
   * 실패가 방금 성공한 세션을 지울 수 있다. 복구가 끝나면 이 화면은 인증 상태에
   * 따라 목적지로 이동하거나 제출을 허용한다.
   */
  const restoring = status === 'restoring'

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    if (restoring) {
      return
    }

    // 비밀번호 형식은 로그인에서 검증하지 않는다. 기존 계정이 현재 정책보다
    // 약할 수 있고, 로그인 화면에서 정책을 노출할 이유도 없다.
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
    <div className="op-shell">
      <div className="op-auth">
        <div className="op-auth__card">
          <aside className="op-auth__hero">
            <div>
              <p className="op-auth__hero-title">운영의 기준을 세우세요.</p>
              <p className="op-auth__hero-text">
                매장 정보·영업시간·예약 접수 시간대·메뉴를 한곳에서 관리합니다.
                변경은 초안으로 저장한 뒤 게시 시점을 직접 정합니다.
              </p>
            </div>
            {/*
              시안 하단의 "업무 효율 98%" 같은 수치는 근거가 되는 계약이 없어
              넣지 않는다. 자리는 그대로 두고 계약이 보장하는 동작만 적는다.
            */}
            <div className="op-auth__hero-points">
              <div>
                <p className="op-auth__hero-point-title">게시 시점 지정</p>
                <p className="op-auth__hero-point-text">
                  초안을 저장한 뒤 게시와 게시 취소를 따로 정합니다.
                </p>
              </div>
              <div>
                <p className="op-auth__hero-point-title">기존 예약 보존</p>
                <p className="op-auth__hero-point-text">
                  설정을 바꿔도 접수된 예약을 자동으로 옮기거나 취소하지
                  않습니다.
                </p>
              </div>
            </div>
          </aside>

          <div className="op-auth__panel">
            <form
              className="op-auth__form"
              onSubmit={handleSubmit}
              aria-label="식당 대표자 로그인"
              noValidate
            >
              <p className="op-auth__brand">
                <span className="op-sidebar__mark" aria-hidden="true">
                  MY
                </span>
                MiriYum Partner
              </p>

              <div>
                <h1 className="op-auth__title">식당 대표자 로그인</h1>
                <p className="op-auth__subtitle">
                  대표자 계정으로 로그인해 매장을 관리하세요.
                </p>
              </div>

              {formError !== null && <Alert tone="error" title={formError} />}

              {signOutPending && (
                <Alert
                  tone="warning"
                  title="이전 세션 로그아웃을 완료하지 못했습니다."
                  actions={
                    <Button
                      type="button"
                      variant="secondary"
                      loading={retryingSignOut}
                      onClick={() => {
                        setRetryingSignOut(true)
                        void signOut()
                          .catch(() => {
                            setFormError(
                              '서버 로그아웃을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.',
                            )
                          })
                          .finally(() => setRetryingSignOut(false))
                      }}
                    >
                      로그아웃 다시 시도
                    </Button>
                  }
                >
                  <p>
                    남아 있는 세션이 자동 복원되지 않도록 차단했습니다. 서버
                    로그아웃을 다시 완료해 주세요.
                  </p>
                </Alert>
              )}

              <TextField
                label="이메일"
                type="email"
                name="email"
                autoComplete="email"
                placeholder="admin@restaurant.com"
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

              <Button
                type="submit"
                variant="primary"
                block
                disabled={restoring}
                loading={submitting}
              >
                로그인
                <OperatorIcon name="arrow-right" />
              </Button>
              {restoring && (
                <p className="op-auth__switch" role="status">
                  로그인 상태를 확인하는 중입니다. 잠시 후 다시 시도해 주세요.
                </p>
              )}

              <p className="op-auth__switch">
                대표자 계정이 없으신가요?{' '}
                <Link to={ROUTES.storeOperatorSignUp}>대표자 회원가입</Link>
              </p>
              <p className="op-auth__switch">
                {/* 일반 사용자와 셸이 다르다. 같은 폼에서 역할을 고르게 하지 않는다. */}
                <Link to={ROUTES.consumerSignIn}>일반 사용자 로그인</Link>
              </p>

              {/*
                시안의 하단 링크 줄. 이용약관·개인정보처리방침 화면은 아직 route가
                없으므로 링크를 만들지 않고 표기만 남긴다. 눌러도 아무 일이 없는
                링크를 두면 없는 화면을 약속하게 된다.
              */}
              <p className="op-auth__footer">
                <span>© MiriYum Partners</span>
              </p>
            </form>
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * 서버 code로 분기한다.
 *
 * `AUTH_005`는 계정 존재 여부를 나누지 않는다. 만료 재발급 실패로 이 화면에
 * 왔더라도 사유를 지어내지 않고 서버가 준 코드만 문구로 옮긴다.
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
    case AuthErrorCode.TOKEN_NAMESPACE_MISMATCH:
      return '대표자 계정으로 다시 로그인해 주세요.'
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
