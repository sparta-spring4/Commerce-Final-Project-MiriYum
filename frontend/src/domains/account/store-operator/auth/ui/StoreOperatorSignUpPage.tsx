import { STORE_OPERATOR_PATHS } from '../../../../../app/routes/paths/storeOperatorPaths'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { isApiError, isNetworkError } from '../../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../../shared/api/envelope'
import { Button } from '../../../../../shared/ui/Button'
import { TextField } from '../../../../../shared/ui/Field'
import { Alert } from '../../../../../shared/ui/Feedback'
import { AccountErrorCode } from '../../../../../shared/auth/authErrors'
import {
  collectErrors,
  normalizePhoneNumber,
  validateEmail,
  validatePassword,
  validatePasswordConfirm,
  validatePhoneNumber,
} from '../../../../../shared/auth/validation'
import { signUpStoreOperator } from '../api/storeOperatorAuthApi'
import { fieldErrorsFromApiError } from '../../../../../shared/api/fieldErrors'
import { validateDisplayName } from '../model/validation'
import { OperatorIcon } from '../../../../../app/shells/store-operator/OperatorIcon'

/**
 * 식당 대표자 회원가입.
 *
 * 요청 본문은 계약이 정한 다섯 필드뿐이다. 스키마가 `additionalProperties: false`
 * 이므로 이메일 인증 참조·본인확인 참조 같은 임의 필드를 추가하지 않는다.
 *
 * 계정 생성과 매장 등록을 한 요청으로 합치지 않는다. 가입이 실패해도 매장
 * 데이터가 지워지지 않고, 매장 등록이 실패해도 계정이 남는다.
 */
export function StoreOperatorSignUpPage() {
  const navigate = useNavigate()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [phoneNumber, setPhoneNumber] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    const errors = collectErrors({
      email: validateEmail(email),
      password: validatePassword(password),
      passwordConfirm: validatePasswordConfirm(password, passwordConfirm),
      phoneNumber: validatePhoneNumber(phoneNumber),
      displayName: validateDisplayName(displayName),
    })
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) {
      return
    }

    setSubmitting(true)
    setFormError(null)
    try {
      await signUpStoreOperator({
        email,
        password,
        passwordConfirm,
        phoneNumber: normalizePhoneNumber(phoneNumber),
        displayName,
      })
      // 가입 응답은 계정 식별자만 준다. 자동 로그인하지 않고 로그인으로 보낸다.
      void navigate(STORE_OPERATOR_PATHS.signIn, {
        replace: true,
        state: { signedUp: true },
      })
    } catch (error) {
      setFormError(signUpErrorMessage(error))
      setFieldErrors(mapServerFieldErrors(error))
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
              <p className="op-auth__hero-title">매장 관리를 시작하세요.</p>
              <p className="op-auth__hero-text">
                대표자 계정을 만든 뒤 매장을 등록합니다. 1차 서비스는 플랫폼 심사
                없이 사업자등록번호 확인만으로 바로 등록됩니다.
              </p>
            </div>

            <div className="op-auth__hero-points">
              <div>
                <p className="op-auth__hero-point-title">계정 먼저, 매장 다음</p>
                <p className="op-auth__hero-point-text">
                  가입과 매장 등록을 한 요청으로 합치지 않아 한쪽이 실패해도
                  다른 쪽이 남습니다.
                </p>
              </div>
              <div>
                <p className="op-auth__hero-point-title">바로 등록</p>
                <p className="op-auth__hero-point-text">
                  1차 서비스는 별도 심사 대기 없이 등록 즉시 관리 화면을
                  엽니다.
                </p>
              </div>
            </div>
          </aside>

          <div className="op-auth__panel">
            <form
              className="op-auth__form"
              onSubmit={handleSubmit}
              aria-label="식당 대표자 회원가입"
              noValidate
            >
              <p className="op-auth__brand">
                <span className="op-sidebar__mark" aria-hidden="true">
                  MY
                </span>
                MiriYum Partner
              </p>

              <div>
                <h1 className="op-auth__title">식당 대표자 회원가입</h1>
                <p className="op-auth__subtitle">
                  매장 관리를 시작하려면 계정을 만들어 주세요.
                </p>
              </div>

              {formError !== null && <Alert tone="error" title={formError} />}

              <TextField
                label="이메일"
                type="email"
                name="email"
                autoComplete="email"
                placeholder="example@restaurant.com"
                required
                value={email}
                error={fieldErrors.email ?? null}
                onChange={(event) => setEmail(event.target.value)}
              />

              <TextField
                label="대표자명"
                name="displayName"
                autoComplete="name"
                placeholder="홍길동"
                required
                value={displayName}
                help="2~50자로 입력해 주세요."
                error={fieldErrors.displayName ?? null}
                onChange={(event) => setDisplayName(event.target.value)}
              />

              <TextField
                label="휴대전화 번호"
                type="tel"
                name="phoneNumber"
                autoComplete="tel"
                placeholder="010-0000-0000"
                required
                value={phoneNumber}
                help="010으로 시작하는 11자리 번호"
                error={fieldErrors.phoneNumber ?? null}
                onChange={(event) => setPhoneNumber(event.target.value)}
              />

              <TextField
                label="비밀번호"
                type="password"
                name="password"
                autoComplete="new-password"
                required
                value={password}
                help="8~64자, 대문자·소문자·숫자·특수문자 가운데 3종 이상"
                error={fieldErrors.password ?? null}
                onChange={(event) => setPassword(event.target.value)}
              />

              <TextField
                label="비밀번호 확인"
                type="password"
                name="passwordConfirm"
                autoComplete="new-password"
                required
                value={passwordConfirm}
                error={fieldErrors.passwordConfirm ?? null}
                onChange={(event) => setPasswordConfirm(event.target.value)}
              />

              <Button type="submit" variant="primary" block loading={submitting}>
                가입하기
                <OperatorIcon name="arrow-right" />
              </Button>

              <p className="op-auth__switch">
                이미 계정이 있으신가요?{' '}
                <Link to={STORE_OPERATOR_PATHS.signIn}>대표자 로그인</Link>
              </p>

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
 * 서버가 준 필드 오류를 폼 필드 이름으로 옮긴다.
 *
 * 계약의 필드 이름이 폼 상태 이름과 같으므로 별도 표를 두지 않는다.
 * 모르는 필드 경로는 무시하고 전체 오류 문구만 남긴다.
 */
function mapServerFieldErrors(error: unknown): Record<string, string> {
  const known = new Set([
    'email',
    'password',
    'passwordConfirm',
    'phoneNumber',
    'displayName',
  ])
  const mapped: Record<string, string> = {}
  for (const [field, reason] of Object.entries(fieldErrorsFromApiError(error))) {
    if (known.has(field)) {
      mapped[field] = reason
    }
  }
  return mapped
}

function signUpErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 가입 처리 여부가 확정되지 않았습니다.'
  }
  if (!isApiError(error)) {
    return '가입에 실패했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case AccountErrorCode.EMAIL_ALREADY_EXISTS:
      return '이미 사용 중인 이메일입니다.'
    case AccountErrorCode.PHONE_ALREADY_EXISTS:
      return '이미 사용 중인 휴대전화 번호입니다.'
    case CommonErrorCode.VALIDATION_FAILED:
      return '입력한 내용을 다시 확인해 주세요.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    case CommonErrorCode.SERVICE_UNAVAILABLE:
      return '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
    default:
      return '가입에 실패했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
