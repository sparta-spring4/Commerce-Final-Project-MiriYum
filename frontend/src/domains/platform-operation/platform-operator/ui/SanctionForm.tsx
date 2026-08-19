import { useState } from 'react'
import { isApiError, isNetworkError } from '../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../shared/api/envelope'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import { AuthErrorCode } from '../../../../shared/auth/authErrors'
import { usePlatformOperatorAuth } from '../../../account/platform-operator/auth'
import {
  createMemberSanction,
  type AccountType,
  type RestrictedFeature,
  type SanctionLevel,
  type SanctionRequest,
} from '../api/memberSupportApi'
import { accountTargetType } from '../api/reauthenticationApi'
import { useLogicalCommandAttempt } from './OperatorCommandFields'
import { ReauthenticationDialog } from './ReauthenticationDialog'
import './page.css'

const LEVEL_LABEL: Record<SanctionLevel, string> = {
  WARNING: '경고',
  FEATURE_RESTRICTION: '기능 제한',
  TEMPORARY_SUSPENSION: '기간 정지',
  PERMANENT_SUSPENSION: '영구 정지 (제안)',
}
const RESTRICTED_FEATURE_LABEL: Record<RestrictedFeature, string> = {
  RESERVATION: '예약',
  WAITING: '웨이팅',
  PICKUP: '픽업',
  STORE_OPERATION: '매장 운영',
  MENU_OPERATION: '메뉴 운영',
}

/** 계약이 사유 코드를 대문자·숫자·밑줄 1~100자로 제한한다. */
const REASON_CODE_PATTERN = /^[A-Z0-9_]+$/

/**
 * 회원 제재 적용.
 *
 * 경고·기능 제한·기간 정지는 한 명이 적용하지만, 영구 정지는 제안만 되고
 * 제안자와 다른 슈퍼관리자의 추가 승인을 받아야 확정된다. 그래서 버튼 문구를
 * 수준에 따라 바꾸고, 성공 응답의 status를 그대로 보여 준다. 화면이 "정지 완료"로
 * 단정하면 실제로는 대기 중인 제재를 처리됐다고 오인한다.
 *
 * 성공을 낙관 확정하지 않는다. 명령이 끝나면 부모가 서버에서 회원 상세를
 * 다시 읽어 최종 상태를 표시한다.
 */
export function SanctionForm({
  accountType,
  accountId,
  supportVersion,
  onApplied,
}: {
  accountType: AccountType
  accountId: string
  supportVersion: number
  onApplied: () => void
}) {
  const { apiClient } = usePlatformOperatorAuth()
  const [level, setLevel] = useState<SanctionLevel>('WARNING')
  const [reasonCode, setReasonCode] = useState('')
  const [policyVersion, setPolicyVersion] = useState('')
  const [restrictedFeatures, setRestrictedFeatures] = useState<
    RestrictedFeature[]
  >([])
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [awaitingReauthentication, setAwaitingReauthentication] =
    useState(false)

  const {
    attempt,
    beginAttempt,
    clearAttempt,
    isAttemptCurrent,
    markInputChanged,
  } = useLogicalCommandAttempt(
    () => ({ idempotencyKey: createIdempotencyKey() }),
    `${accountType}:${accountId}:${supportVersion}`,
  )

  function validate(): boolean {
    const errors: Record<string, string> = {}
    if (reasonCode.length === 0) {
      errors.reasonCode = '사유 코드를 입력해 주세요.'
    } else if (!REASON_CODE_PATTERN.test(reasonCode)) {
      errors.reasonCode = '대문자·숫자·밑줄만 사용할 수 있습니다.'
    }
    if (policyVersion.length === 0) {
      errors.policyVersion = '정책 version을 입력해 주세요.'
    }
    if (level === 'FEATURE_RESTRICTION' && restrictedFeatures.length === 0) {
      errors.restrictedFeatures = '제한할 기능을 하나 이상 선택해 주세요.'
    }
    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  function handleRequestReauthentication(event: React.FormEvent) {
    event.preventDefault()
    setFormError(null)
    setResult(null)
    if (!validate()) {
      return
    }
    beginAttempt()
    setAwaitingReauthentication(true)
  }

  async function handleApproved(approval: string) {
    if (attempt === null || !isAttemptCurrent()) {
      setAwaitingReauthentication(false)
      clearAttempt()
      setFormError(
        '재인증 중 명령 입력이 변경됐습니다. 변경된 내용으로 다시 제출해 주세요.',
      )
      return
    }
    setAwaitingReauthentication(false)
    setSubmitting(true)
    setFormError(null)
    try {
      const body: SanctionRequest = {
        level,
        reasonCode,
        policyVersion,
        ...(restrictedFeatures.length > 0 ? { restrictedFeatures } : {}),
      }
      const sanction = await createMemberSanction(apiClient, {
        accountType,
        accountId,
        supportVersion,
        reauthenticationApproval: approval,
        idempotencyKey: attempt.idempotencyKey,
        body,
      })
      // 서버가 준 status를 그대로 전한다. 영구 정지는 여기서 APPLIED가 아니다.
      setResult(
        sanction.status === 'PENDING_ADDITIONAL_APPROVAL'
          ? `제재 ${sanction.sanctionId}를 추가 승인 대기로 제안했습니다. 다른 슈퍼관리자가 승인해야 적용됩니다.`
          : `제재 ${sanction.sanctionId}가 ${sanction.status} 상태로 기록됐습니다.`,
      )
      clearAttempt()
      setReasonCode('')
      setPolicyVersion('')
      setRestrictedFeatures([])
      onApplied()
    } catch (error) {
      setFormError(sanctionErrorMessage(error))
      // version 충돌이면 최신 상세를 다시 읽어야 재시도할 수 있다.
      if (isApiError(error) && error.status === 409) {
        onApplied()
      }
    } finally {
      setSubmitting(false)
    }
  }

  function toggleFeature(feature: RestrictedFeature) {
    markInputChanged()
    setRestrictedFeatures((current) =>
      current.includes(feature)
        ? current.filter((item) => item !== feature)
        : [...current, feature],
    )
  }

  return (
    <section aria-labelledby="sanction-form-heading">
      <h2 className="po-section__title" id="sanction-form-heading">
        제재 적용
      </h2>

      {result !== null && <Alert tone="info" title={result} />}
      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="po-form"
        onSubmit={handleRequestReauthentication}
        aria-label="제재 적용"
        inert={awaitingReauthentication}
        noValidate
      >
        <SelectField
          label="제재 수준"
          value={level}
          onChange={(event) => {
            markInputChanged()
            setLevel(event.target.value as SanctionLevel)
          }}
        >
          {Object.entries(LEVEL_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>

        <TextField
          label="사유 코드"
          name="reasonCode"
          help="대문자·숫자·밑줄만 사용합니다. 예: ABUSE_REPORT"
          value={reasonCode}
          error={fieldErrors.reasonCode ?? null}
          onChange={(event) => {
            markInputChanged()
            setReasonCode(event.target.value)
          }}
        />

        <TextField
          label="정책 version"
          name="policyVersion"
          value={policyVersion}
          error={fieldErrors.policyVersion ?? null}
          onChange={(event) => {
            markInputChanged()
            setPolicyVersion(event.target.value)
          }}
        />

        <fieldset className="po-fieldset">
          <legend className="po-fieldset__legend">제한 기능</legend>
          {fieldErrors.restrictedFeatures !== undefined && (
            <p className="po-fieldset__error" role="alert">
              {fieldErrors.restrictedFeatures}
            </p>
          )}
          {Object.entries(RESTRICTED_FEATURE_LABEL).map(([value, label]) => (
            <label key={value} className="po-checkbox">
              <input
                type="checkbox"
                checked={restrictedFeatures.includes(
                  value as RestrictedFeature,
                )}
                onChange={() => toggleFeature(value as RestrictedFeature)}
              />
              {label}
            </label>
          ))}
        </fieldset>

        <p className="po-form__notice">
          support version {supportVersion} 기준으로 처리합니다. 그 사이 상태가
          바뀌면 서버가 거절하고 최신 상태를 다시 읽습니다.
        </p>

        <Button
          type="submit"
          variant="primary"
          size="lg"
          loading={submitting}
          disabled={awaitingReauthentication}
        >
          {level === 'PERMANENT_SUSPENSION' ? '영구 정지 제안' : '제재 적용'}
        </Button>
      </form>

      {awaitingReauthentication && attempt !== null && (
        <ReauthenticationDialog
          purpose="ACCOUNT_SANCTION"
          targetType={accountTargetType(accountType)}
          targetId={accountId}
          description={`${LEVEL_LABEL[level]} · 사유 ${reasonCode} 처리를 위해 본인 확인이 필요합니다.`}
          onApproved={handleApproved}
          onCancel={() => setAwaitingReauthentication(false)}
        />
      )}
    </section>
  )
}

function sanctionErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 처리 여부를 상태에서 다시 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '제재를 적용하지 못했습니다.'
  }
  switch (error.code) {
    case AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT:
      return '대상 상태가 변경됐습니다. 최신 정보를 확인한 뒤 다시 시도해 주세요.'
    case AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND:
      return '대상을 찾을 수 없습니다.'
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '같은 키로 다른 내용을 보냈습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.'
    case CommonErrorCode.CONCURRENT_MODIFICATION:
      return '동시 요청이 충돌했습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요.'
    default:
      return error.message
  }
}
