import { useState } from 'react'
import { isApiError } from '../../../../../shared/api/apiError'
import { useIdempotentAttempt } from '../../../../../shared/api/useIdempotentAttempt'
import { Button } from '../../../../../shared/ui/Button'
import { TextField } from '../../../../../shared/ui/Field'
import { Alert } from '../../../../../shared/ui/Feedback'
import { Icon } from '../../../../../shared/ui/Icon'
import { AccountErrorCode } from '../../../../../shared/auth/authErrors'
import {
  normalizePhoneNumber,
  validateNickname,
  validatePhoneNumber,
} from '../../../../../shared/auth/validation'
import {
  useRegisterContact,
  useUpdateNickname,
  type ConsumerAccount,
} from '../api/queries'

/**
 * 내 프로필.
 *
 * 1차 MVP에서 바꿀 수 있는 값은 닉네임뿐이다. 이메일·비밀번호 변경 폼은 만들지 않는다.
 * 연락처는 미등록 계정의 최초 등록만 제공하고 변경 폼으로 재사용하지 않는다.
 */
export function ProfileSection({ account }: { account: ConsumerAccount }) {
  return (
    <section className="mi-card mi-card--roomy profile" aria-label="내 프로필">
      {/* 시안은 프로필 카드 오른쪽 위에 옅은 광원을 하나 둔다. */}
      <span className="profile__glow" aria-hidden="true" />

      <div className="mi-card__body mi-card__body--roomy">
        <h2>
          <Icon name="person" />
          내 프로필
        </h2>

        <dl className="profile__list">
          <div className="profile__entry">
            <dt>이메일</dt>
            <dd>{account.email}</dd>
            <span className="profile__readonly">읽기 전용</span>
          </div>

          <div className="profile__entry">
            <dt>휴대전화</dt>
            {/* 서버가 마스킹한 값만 표시한다. 원문을 요구하거나 조합하지 않는다. */}
            <dd>{account.phoneNumber ?? '등록되지 않음'}</dd>
            {account.phoneNumber !== null && (
              <span className="profile__readonly">읽기 전용</span>
            )}
          </div>
        </dl>

        <NicknameEditor account={account} />

        {account.phoneNumber === null && <ContactRegistration />}
      </div>
    </section>
  )
}

function NicknameEditor({ account }: { account: ConsumerAccount }) {
  const [editing, setEditing] = useState(false)
  const [nickname, setNickname] = useState(account.nickname)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  /*
   * 닉네임은 요청 본문이라 요청 지문의 일부다. 값을 고쳐 다시 제출하면 다른
   * 명령이므로 같은 키를 쓸 수 없고(`COMMON_007`), 결과 불명 뒤에 새 키를
   * 발급하면 변경이 두 번 나갈 수 있다. attempt가 두 규칙을 함께 지킨다.
   */
  const attempt = useIdempotentAttempt(nickname)

  const mutation = useUpdateNickname()

  function startEditing() {
    setNickname(account.nickname)
    setFieldError(null)
    setFormError(null)
    setEditing(true)
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    const error = validateNickname(nickname)
    setFieldError(error)
    setFormError(null)
    if (error !== null) {
      return
    }
    if (nickname === account.nickname) {
      setFormError('현재 닉네임과 같습니다.')
      return
    }

    const idempotencyKey = attempt.begin()
    if (idempotencyKey === null) {
      setFormError(
        '앞선 변경 요청의 처리 여부를 확인하지 못했습니다. 닉네임을 바꿔 다시 보내면 두 번 변경될 수 있습니다. 잠시 후 마이페이지를 새로 고쳐 확인해 주세요.',
      )
      return
    }

    mutation.mutate(
      { nickname, idempotencyKey },
      {
        onSuccess: () => {
          attempt.settle(null)
          setEditing(false)
        },
        onError: (error) => {
          attempt.settle(error)
          setFormError(nicknameErrorMessage(error))
        },
      },
    )
  }

  if (!editing) {
    return (
      <div className="profile__row">
        <div>
          <p className="profile__label">닉네임</p>
          <p className="profile__nickname">
            {account.nickname}
            {/*
              시안은 연필 아이콘 버튼만 둔다. 아이콘만으로는 무엇을 여는지
              전달되지 않으므로 접근 가능한 이름을 붙인다.
            */}
            <button
              type="button"
              className="mi-icon-button profile__edit"
              aria-label="닉네임 변경"
              onClick={startEditing}
            >
              <Icon name="edit" />
            </button>
          </p>
          <p className="profile__note">
            <Icon name="info" className="mi-icon--sm" />
            닉네임은 변경 후 일정 기간 다시 바꿀 수 없습니다.
          </p>
        </div>
      </div>
    )
  }

  return (
    <form className="profile__form" onSubmit={handleSubmit} aria-label="닉네임 변경" noValidate>
      {formError !== null && <Alert tone="error" title={formError} />}

      <TextField
        label="새 닉네임"
        name="nickname"
        value={nickname}
        error={fieldError}
        help="닉네임은 변경 후 일정 기간 다시 바꿀 수 없습니다."
        onChange={(event) => setNickname(event.target.value)}
      />

      <div className="profile__actions">
        <Button type="submit" variant="primary" loading={mutation.isPending}>
          저장하기
        </Button>
        <Button variant="ghost" onClick={() => setEditing(false)}>
          취소
        </Button>
      </div>
    </form>
  )
}

/**
 * 최초 연락처 등록.
 *
 * 기존 계정에 연락처가 없으면 예약 생성이 `ACCOUNT_006`으로 막힌다.
 * 그 복구 지점을 프로필에 둔다.
 */
function ContactRegistration() {
  const [phoneNumber, setPhoneNumber] = useState('')
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  /*
   * 번호가 요청 지문이다. 닉네임 변경과 같은 규칙을 쓴다.
   * 번호가 바뀌면 새 키, 결과 불명 뒤 번호 변경은 차단이다.
   */
  const normalizedPhoneNumber = normalizePhoneNumber(phoneNumber)
  const attempt = useIdempotentAttempt(normalizedPhoneNumber)

  const mutation = useRegisterContact()

  function updatePhoneNumber(next: string) {
    setPhoneNumber(next)
    setFormError(null)
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    const error = validatePhoneNumber(phoneNumber)
    setFieldError(error)
    setFormError(null)
    if (error !== null) {
      return
    }

    const idempotencyKey = attempt.begin()
    if (idempotencyKey === null) {
      setFormError(
        '앞선 등록 요청의 처리 여부를 확인하지 못했습니다. 번호를 바꿔 다시 보내면 등록이 두 번 처리될 수 있습니다. 잠시 후 마이페이지를 새로 고쳐 확인해 주세요.',
      )
      return
    }

    mutation.mutate(
      { phoneNumber: normalizedPhoneNumber, idempotencyKey },
      {
        onSuccess: () => attempt.settle(null),
        onError: (error) => {
          attempt.settle(error)
          setFormError(contactErrorMessage(error))
        },
      },
    )
  }

  return (
    <form
      className="profile__form"
      onSubmit={handleSubmit}
      aria-label="연락처 등록"
      noValidate
    >
      <Alert tone="info" title="예약하려면 연락처 등록이 필요합니다.">
        <p>최초 등록한 번호는 이후 변경할 수 없습니다.</p>
      </Alert>

      {formError !== null && <Alert tone="error" title={formError} />}

      <TextField
        label="휴대전화 번호"
        type="tel"
        name="phoneNumber"
        autoComplete="tel"
        help="010으로 시작하는 11자리 번호"
        value={phoneNumber}
        error={fieldError}
        onChange={(event) => updatePhoneNumber(event.target.value)}
      />

      <Button type="submit" variant="primary" loading={mutation.isPending}>
        연락처 등록
      </Button>
    </form>
  )
}

/**
 * ACCOUNT_005는 다음 변경 가능 시각을 별도 필드로 주지 않는다.
 * 서버 메시지를 그대로 보여 주고 클라이언트가 시각을 계산해 지어내지 않는다.
 */
function nicknameErrorMessage(error: unknown): string {
  if (isApiError(error) && error.code === AccountErrorCode.NICKNAME_CHANGE_TOO_SOON) {
    return error.message
  }
  return '닉네임을 변경하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

function contactErrorMessage(error: unknown): string {
  if (!isApiError(error)) {
    return '연락처를 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case AccountErrorCode.PHONE_ALREADY_EXISTS:
      return '이미 가입된 휴대전화 번호입니다.'
    case AccountErrorCode.CONTACT_CHANGE_NOT_ALLOWED:
      return '이미 등록한 연락처는 변경할 수 없습니다.'
    default:
      return '연락처를 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
