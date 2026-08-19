import { PUBLIC_PATHS } from '../../../../../app/routes/paths/publicPaths'
import { CONSUMER_PATHS } from '../../../../../app/routes/paths/consumerPaths'
import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { isApiError, isNetworkError } from '../../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../../shared/api/envelope'
import { Button } from '../../../../../shared/ui/Button'
import { TextField } from '../../../../../shared/ui/Field'
import { Alert } from '../../../../../shared/ui/Feedback'
import { Icon } from '../../../../../shared/ui/Icon'
import { createConsumerKakaoAccount } from '../api/consumerAuthApi'
import { useConsumerAuth } from '../../../../../app/shells/consumer/ConsumerAuthProvider'
import { AccountErrorCode } from '../../../../../shared/auth/authErrors'
import {
  collectErrors,
  normalizePhoneNumber,
  validateAgeConfirmed,
  validateEmail,
  validateNickname,
  validatePhoneNumber,
} from '../../../../../shared/auth/validation'

const INITIAL_FORM = { email: '', phoneNumber: '', nickname: '', ageConfirmed: false }

interface KakaoSignUpLocationState {
  signUpTicket?: unknown
}

/** 첫 카카오 로그인에 필요한 서비스 필수정보만 받아 비밀번호 없는 계정을 완성한다. */
export function ConsumerKakaoSignUpPage() {
  const { completeKakaoSignIn } = useConsumerAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [form, setForm] = useState(INITIAL_FORM)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const state = location.state as KakaoSignUpLocationState | null
  const signUpTicket = typeof state?.signUpTicket === 'string' ? state.signUpTicket : null

  function update(patch: Partial<typeof INITIAL_FORM>) {
    setForm((current) => ({ ...current, ...patch }))
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    const errors = collectErrors({
      email: validateEmail(form.email),
      phoneNumber: validatePhoneNumber(form.phoneNumber),
      nickname: validateNickname(form.nickname),
      ageConfirmed: validateAgeConfirmed(form.ageConfirmed),
    })
    setFieldErrors(errors)
    setFormError(null)
    if (signUpTicket === null) {
      setFormError('가입 시간이 만료되었습니다. 카카오 로그인을 다시 시작해 주세요.')
      return
    }
    if (Object.keys(errors).length > 0) {
      return
    }

    setSubmitting(true)
    try {
      const result = await createConsumerKakaoAccount({
        signUpTicket,
        email: form.email,
        phoneNumber: normalizePhoneNumber(form.phoneNumber),
        ageConfirmed: true,
        nickname: form.nickname,
      })
      if (result.status !== 'AUTHENTICATED' || result.accessToken === undefined) {
        throw new Error('Kakao sign-up did not authenticate the consumer')
      }
      await completeKakaoSignIn(result.accessToken)
      void navigate(PUBLIC_PATHS.home, { replace: true })
    } catch (error) {
      const conflict = kakaoConflictFieldError(error)
      setFieldErrors(conflict ?? {})
      if (conflict === null) {
        setFormError(kakaoSignUpErrorMessage(error))
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="mi-ambient" aria-hidden="true" />
      <div className="auth-card mi-ambient-content">
        <header className="auth-card__header">
          <p className="auth-card__mark" aria-hidden="true">MiriYum</p>
          <h1>카카오로 가입하기</h1>
          <p className="auth-card__lead">서비스 이용에 필요한 정보만 입력해 주세요.</p>
        </header>
        <form className="auth-form" onSubmit={handleSubmit} aria-label="카카오 회원가입" noValidate>
          <TextField label="이메일" type="email" name="email" autoComplete="email" required leadingIcon={<Icon name="mail" />} value={form.email} error={fieldErrors.email ?? null} onChange={(event) => update({ email: event.target.value })} />
          <TextField label="휴대전화 번호" type="tel" name="phoneNumber" autoComplete="tel" required help="010으로 시작하는 11자리 번호" leadingIcon={<Icon name="phone" />} value={form.phoneNumber} error={fieldErrors.phoneNumber ?? null} onChange={(event) => update({ phoneNumber: event.target.value })} />
          <TextField label="닉네임" name="nickname" autoComplete="nickname" required help="2~20자, 한글·영문·숫자와 공백, _, -" leadingIcon={<Icon name="person" />} value={form.nickname} error={fieldErrors.nickname ?? null} onChange={(event) => update({ nickname: event.target.value })} />
          <div className="mi-field auth-form__consent">
            <label className="mi-checkbox"><input type="checkbox" className="mi-checkbox__control" name="ageConfirmed" checked={form.ageConfirmed} aria-invalid={fieldErrors.ageConfirmed !== undefined || undefined} aria-describedby={fieldErrors.ageConfirmed !== undefined ? 'kakao-age-confirmed-error' : undefined} onChange={(event) => update({ ageConfirmed: event.target.checked })} />(필수) 만 14세 이상입니다.</label>
            {fieldErrors.ageConfirmed !== undefined && <p id="kakao-age-confirmed-error" className="mi-field__error">{fieldErrors.ageConfirmed}</p>}
          </div>
          {formError !== null && <Alert tone="error" title={formError} />}
          <Button type="submit" variant="primary" size="lg" block loading={submitting}>가입 완료</Button>
        </form>
        <footer className="auth-card__foot"><Link className="auth-card__switch-link" to={CONSUMER_PATHS.signIn}>로그인 화면으로 돌아가기</Link></footer>
      </div>
    </div>
  )
}

function kakaoConflictFieldError(error: unknown): Record<string, string> | null {
  if (!isApiError(error)) return null
  if (error.code === AccountErrorCode.EMAIL_ALREADY_EXISTS) return { email: '이미 가입된 이메일입니다.' }
  if (error.code === AccountErrorCode.PHONE_ALREADY_EXISTS) return { phoneNumber: '이미 가입된 휴대전화 번호입니다.' }
  return null
}

function kakaoSignUpErrorMessage(error: unknown): string {
  if (isNetworkError(error)) return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (isApiError(error) && error.code === CommonErrorCode.TOO_MANY_REQUESTS) return '가입 시도가 많습니다. 잠시 후 다시 시도해 주세요.'
  if (isApiError(error) && error.code === CommonErrorCode.SERVICE_UNAVAILABLE) return '지금은 가입을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  return '카카오 가입을 완료하지 못했습니다. 다시 시도해 주세요.'
}
