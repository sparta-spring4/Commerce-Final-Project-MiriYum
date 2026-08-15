import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { ROUTES } from '../../../app/routes'
import { Button } from '../../../shared/ui/Button'
import { PasswordField, TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { Icon } from '../../../shared/ui/Icon'
import { signUpConsumer } from '../api/consumerAuthApi'
import { AccountErrorCode } from '../model/authErrors'
import {
  collectErrors,
  normalizePhoneNumber,
  validateAgeConfirmed,
  validateEmail,
  validateNickname,
  validatePassword,
  validatePasswordConfirm,
  validatePhoneNumber,
} from '../model/validation'

const INITIAL_FORM = {
  email: '',
  password: '',
  passwordConfirm: '',
  phoneNumber: '',
  nickname: '',
  ageConfirmed: false,
}

/**
 * 일반 사용자 가입.
 *
 * 요청 본문은 계약이 정한 여섯 필드뿐이다. 스키마가 `additionalProperties: false`
 * 이므로 화면 편의를 위한 필드를 덧붙이면 400이 된다.
 *
 * 성공해도 자동 로그인하지 않는다. 로그인 화면으로 보낸다.
 */
export function ConsumerSignUpPage() {
  const navigate = useNavigate()

  const [form, setForm] = useState(INITIAL_FORM)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function update(patch: Partial<typeof INITIAL_FORM>) {
    setForm((current) => ({ ...current, ...patch }))
  }

  function validate(): Record<string, string> {
    return collectErrors({
      email: validateEmail(form.email),
      password: validatePassword(form.password),
      passwordConfirm: validatePasswordConfirm(
        form.password,
        form.passwordConfirm,
      ),
      phoneNumber: validatePhoneNumber(form.phoneNumber),
      nickname: validateNickname(form.nickname),
      ageConfirmed: validateAgeConfirmed(form.ageConfirmed),
    })
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    const errors = validate()
    setFieldErrors(errors)
    setFormError(null)
    if (Object.keys(errors).length > 0) {
      return
    }

    setSubmitting(true)
    try {
      await signUpConsumer({
        email: form.email,
        password: form.password,
        passwordConfirm: form.passwordConfirm,
        // 서버도 정규화하지만 계약 형식으로 맞춰 보낸다.
        phoneNumber: normalizePhoneNumber(form.phoneNumber),
        ageConfirmed: true,
        nickname: form.nickname,
      })
      void navigate(ROUTES.consumerSignIn, { replace: true })
    } catch (error) {
      const conflict = conflictFieldError(error)
      if (conflict !== null) {
        // 중복은 해당 입력 옆에 붙여야 사용자가 무엇을 고칠지 안다.
        setFieldErrors(conflict)
      } else {
        setFormError(signUpErrorMessage(error))
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
          {/* 로그인 화면과 같은 이유로 로고 자리에 워드마크를 둔다. */}
          <p className="auth-card__mark" aria-hidden="true">
            MiriYum
          </p>
          <h1>회원가입</h1>
          <p className="auth-card__lead">
            미리냠과 함께 맛있는 여정을 시작하세요
          </p>
        </header>

        {/*
          noValidate로 브라우저 기본 검증을 끈다. 기본 검증이 먼저 걸리면 우리가
          입력에 연결한 접근 가능한 오류 문구 대신 브라우저 말풍선이 뜬다.
          required 속성은 보조기술이 읽도록 남겨 둔다.
        */}
        <form
          className="auth-form"
          onSubmit={handleSubmit}
          aria-label="회원가입"
          noValidate
        >
          <TextField
            label="이메일"
            type="email"
            name="email"
            autoComplete="email"
            required
            placeholder="example@miriyum.com"
            leadingIcon={<Icon name="mail" />}
            value={form.email}
            error={fieldErrors.email ?? null}
            onChange={(event) => update({ email: event.target.value })}
          />

          <PasswordField
            label="비밀번호"
            name="password"
            autoComplete="new-password"
            required
            help="8~64자, 대문자·소문자·숫자·특수문자 가운데 3종 이상"
            value={form.password}
            error={fieldErrors.password ?? null}
            onChange={(event) => update({ password: event.target.value })}
          />

          <PasswordField
            label="비밀번호 확인"
            name="passwordConfirm"
            autoComplete="new-password"
            required
            placeholder="비밀번호를 한번 더 입력해 주세요"
            value={form.passwordConfirm}
            error={fieldErrors.passwordConfirm ?? null}
            onChange={(event) => update({ passwordConfirm: event.target.value })}
          />

          <TextField
            label="휴대전화 번호"
            type="tel"
            name="phoneNumber"
            autoComplete="tel"
            required
            help="010으로 시작하는 11자리 번호"
            leadingIcon={<Icon name="phone" />}
            value={form.phoneNumber}
            error={fieldErrors.phoneNumber ?? null}
            onChange={(event) => update({ phoneNumber: event.target.value })}
          />

          <TextField
            label="닉네임"
            name="nickname"
            autoComplete="nickname"
            required
            help="2~20자, 한글·영문·숫자와 공백, _, -"
            placeholder="미리냠에서 사용할 닉네임"
            leadingIcon={<Icon name="person" />}
            value={form.nickname}
            error={fieldErrors.nickname ?? null}
            onChange={(event) => update({ nickname: event.target.value })}
          />

          {/* 시안은 동의 항목을 자기 색면 위에 묶어 폼 입력과 구분한다. */}
          <div className="mi-field auth-form__consent">
            <label className="mi-checkbox">
              <input
                type="checkbox"
                className="mi-checkbox__control"
                name="ageConfirmed"
                checked={form.ageConfirmed}
                aria-invalid={
                  fieldErrors.ageConfirmed !== undefined || undefined
                }
                aria-describedby={
                  fieldErrors.ageConfirmed !== undefined
                    ? 'age-confirmed-error'
                    : undefined
                }
                onChange={(event) =>
                  update({ ageConfirmed: event.target.checked })
                }
              />
              (필수) 만 14세 이상입니다.
            </label>
            {fieldErrors.ageConfirmed !== undefined && (
              <p className="mi-field__error" id="age-confirmed-error">
                {fieldErrors.ageConfirmed}
              </p>
            )}
          </div>

          {formError !== null && <Alert tone="error" title={formError} />}

          <Button
            type="submit"
            variant="primary"
            size="lg"
            block
            loading={submitting}
          >
            가입하기
          </Button>
        </form>

        <footer className="auth-card__foot">
          <p className="auth-card__switch">
            이미 계정이 있으신가요?{' '}
            <Link className="auth-card__switch-link" to={ROUTES.consumerSignIn}>
              로그인
            </Link>
          </p>
          <Link className="auth-card__aside" to={ROUTES.storeOperatorSignIn}>
            <Icon name="store" className="mi-icon--sm" />
            식당 대표자 서비스로 이동
          </Link>
        </footer>
      </div>
    </div>
  )
}

/** 중복 오류는 필드 오류로 옮긴다. 그 밖의 오류는 null을 돌려 폼 상단에서 다룬다. */
function conflictFieldError(error: unknown): Record<string, string> | null {
  if (!isApiError(error)) {
    return null
  }
  if (error.code === AccountErrorCode.EMAIL_ALREADY_EXISTS) {
    return { email: '이미 가입된 이메일입니다.' }
  }
  if (error.code === AccountErrorCode.PHONE_ALREADY_EXISTS) {
    return { phoneNumber: '이미 가입된 휴대전화 번호입니다.' }
  }
  return null
}

function signUpErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (!isApiError(error)) {
    return '가입에 실패했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case CommonErrorCode.VALIDATION_FAILED:
      return '입력한 내용을 다시 확인해 주세요.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '가입 시도가 많습니다. 잠시 후 다시 시도해 주세요.'
    case CommonErrorCode.SERVICE_UNAVAILABLE:
      // 본인확인 제공업체 미선정 등으로 가입이 막힌 상태를 성공으로 바꾸지 않는다.
      return '지금은 가입을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.'
    default:
      return '가입에 실패했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
