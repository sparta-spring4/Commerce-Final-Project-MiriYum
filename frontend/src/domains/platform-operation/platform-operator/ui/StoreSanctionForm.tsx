import { useState } from 'react'
import { isApiError, isNetworkError } from '../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../shared/api/envelope'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import { usePlatformOperatorAuth } from '../../../account/platform-operator/auth'
import {
  createStoreSanction,
  createStoreSanctionImpactPreview,
  type StoreCaseContext,
  type StoreRestrictedFeature,
  type StoreSanctionImpactPreview,
  type StoreSanctionType,
} from '../api/storeAdminApi'
import {
  STORE_FEATURE_LABEL,
  STORE_RESTRICTED_FEATURES,
  STORE_SANCTION_TYPES,
  STORE_SANCTION_TYPE_LABEL,
  isHighRiskStoreSanction,
} from '../model/storeLabels'
import { useLogicalCommandAttempt } from './OperatorCommandFields'
import { ReauthenticationDialog } from './ReauthenticationDialog'
import { StoreSanctionImpactPreviewView } from './StoreSanctionImpactPreview'
import './page.css'

/**
 * 제재 제안·적용.
 *
 * 흐름을 `입력 → 영향 확인 → 재인증 → 실행` 네 단계로 나눈다. 한 버튼으로
 * 합치면 운영자가 몇 건이 취소되는지 모르는 채 실행하게 된다.
 *
 * 재인증은 계약상 optional이다. 고위험 제재(영구 퇴점, 전체 기능 제한)에서만
 * 서버가 요구하므로 화면이 유형을 보고 판단해 그때만 다이얼로그를 띄운다.
 *
 * 409 충돌이나 미리보기 만료·digest 불일치에서는 입력값으로 자동 재전송하지
 * 않는다. 그 사이 매장 상태가 바뀌었으므로 새 미리보기가 필요하다.
 */
export function StoreSanctionForm({
  storeId,
  context,
  onApplied,
}: {
  storeId: number
  context: StoreCaseContext
  onApplied: () => void
}) {
  const { apiClient } = usePlatformOperatorAuth()

  const [type, setType] = useState<StoreSanctionType>('WARNING')
  const [features, setFeatures] = useState<StoreRestrictedFeature[]>([])
  const [startsAt, setStartsAt] = useState('')
  const [endsAt, setEndsAt] = useState('')
  const [reason, setReason] = useState('')

  const [preview, setPreview] = useState<StoreSanctionImpactPreview | null>(
    null,
  )
  const [previewing, setPreviewing] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
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
  } = useLogicalCommandAttempt(
    () => ({ idempotencyKey: createIdempotencyKey() }),
    JSON.stringify({
      storeId,
      context,
      type,
      features,
      startsAt,
      endsAt,
      reason,
      previewId: preview?.previewId,
      previewDigest: preview?.digest,
      previewCaseVersion: preview?.caseVersion,
      previewEnforcementVersion: preview?.storeEnforcementVersion,
    }),
  )

  const highRisk = isHighRiskStoreSanction(type, features)
  const previewExpired =
    preview !== null && new Date(preview.expiresAt).getTime() <= Date.now()

  function shape() {
    return {
      type,
      restrictedFeatures: features,
      ...(startsAt.length > 0 ? { startsAt: new Date(startsAt).toISOString() } : {}),
      ...(endsAt.length > 0 ? { endsAt: new Date(endsAt).toISOString() } : {}),
    }
  }

  function validate(): boolean {
    const next: Record<string, string> = {}
    if (type === 'FEATURE_RESTRICTION' && features.length === 0) {
      next.features = '제한할 기능을 하나 이상 선택해 주세요.'
    }
    if (reason.trim().length === 0) {
      next.reason = '제재 사유를 입력해 주세요.'
    }
    setErrors(next)
    return Object.keys(next).length === 0
  }

  function toggleFeature(feature: StoreRestrictedFeature) {
    setFeatures((current) =>
      current.includes(feature)
        ? current.filter((item) => item !== feature)
        : [...current, feature],
    )
    // 조건이 바뀌면 이전 미리보기는 더 이상 이 제재의 영향이 아니다.
    setPreview(null)
  }

  async function handlePreview(event: React.FormEvent) {
    event.preventDefault()
    setResult(null)
    setFormError(null)
    if (!validate()) {
      return
    }
    setPreviewing(true)
    try {
      const created = await createStoreSanctionImpactPreview(apiClient, {
        storeId,
        context,
        body: shape(),
      })
      setPreview(created)
    } catch (error) {
      setFormError(sanctionErrorMessage(error))
    } finally {
      setPreviewing(false)
    }
  }

  function handleRequestExecute() {
    if (preview === null || previewExpired) {
      setFormError('먼저 유효한 영향 미리보기를 만들어 주세요.')
      return
    }
    setFormError(null)
    const commandAttempt = beginAttempt()
    if (highRisk) {
      setAwaitingReauthentication(true)
      return
    }
    // 고위험이 아니면 서버가 재인증을 요구하지 않는다.
    void execute(undefined, commandAttempt)
  }

  function handleApproved(approval: string) {
    if (attempt === null || !isAttemptCurrent()) {
      setAwaitingReauthentication(false)
      clearAttempt()
      setFormError(
        '재인증 중 명령 입력이 변경됐습니다. 변경된 내용으로 다시 제출해 주세요.',
      )
      return
    }
    void execute(approval, attempt)
  }

  async function execute(
    approval: string | undefined,
    commandAttempt: { idempotencyKey: string },
  ) {
    setAwaitingReauthentication(false)
    if (preview === null) {
      return
    }
    setSubmitting(true)
    setFormError(null)
    try {
      const sanction = await createStoreSanction(apiClient, {
        storeId,
        context,
        idempotencyKey: commandAttempt.idempotencyKey,
        reauthenticationApproval: approval,
        body: {
          ...shape(),
          reason: reason.trim(),
          impactConfirmation: {
            previewId: preview.previewId,
            previewDigest: preview.digest,
            caseVersion: preview.caseVersion,
            storeEnforcementVersion: preview.storeEnforcementVersion,
          },
        },
      })
      // 서버가 준 status를 그대로 전한다. 승인 대기일 수 있다.
      setResult(
        sanction.status === 'PENDING_APPROVAL'
          ? `제재 ${sanction.sanctionId}를 제안했습니다. 다른 슈퍼관리자의 승인 후 적용됩니다.`
          : `제재 ${sanction.sanctionId}가 ${sanction.status} 상태로 기록됐습니다.`,
      )
      clearAttempt()
      setPreview(null)
      setReason('')
    } catch (error) {
      setFormError(sanctionErrorMessage(error))
      // 충돌이면 미리보기가 낡았다. 자동 재전송하지 않고 다시 만들게 한다.
      if (isApiError(error) && error.status === 409) {
        setPreview(null)
      }
    } finally {
      setSubmitting(false)
      onApplied()
    }
  }

  return (
    <section aria-labelledby="store-sanction-form-heading">
      <h2 className="po-section__title" id="store-sanction-form-heading">
        제재 적용
      </h2>

      <Alert tone="warning" title="거래에 영향을 주는 작업입니다.">
        제재를 적용하면 매장의 예약·웨이팅·픽업이 제한될 수 있습니다. 실행 전에
        영향 건수를 반드시 확인해 주세요.
        {highRisk && (
          <>
            {' '}
            <strong>
              선택한 제재는 고위험이라 제안자와 다른 슈퍼관리자의 추가 승인이
              필요합니다.
            </strong>
          </>
        )}
      </Alert>

      {result !== null && <Alert tone="info" title={result} />}
      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="po-form"
        onSubmit={handlePreview}
        aria-label="제재 조건"
        inert={awaitingReauthentication}
        noValidate
      >
        <SelectField
          label="제재 유형"
          value={type}
          onChange={(event) => {
            setType(event.target.value as StoreSanctionType)
            setPreview(null)
          }}
        >
          {STORE_SANCTION_TYPES.map((value) => (
            <option key={value} value={value}>
              {STORE_SANCTION_TYPE_LABEL[value]}
            </option>
          ))}
        </SelectField>

        <fieldset className="po-fieldset">
          <legend className="po-fieldset__legend">제한 기능</legend>
          {errors.features !== undefined && (
            <p className="po-fieldset__error" role="alert">
              {errors.features}
            </p>
          )}
          {STORE_RESTRICTED_FEATURES.map((feature) => (
            <label key={feature} className="po-checkbox">
              <input
                type="checkbox"
                checked={features.includes(feature)}
                onChange={() => toggleFeature(feature)}
              />
              {STORE_FEATURE_LABEL[feature]}
            </label>
          ))}
        </fieldset>

        <TextField
          label="시작 시각"
          type="datetime-local"
          name="startsAt"
          help="비우면 즉시 시작합니다."
          value={startsAt}
          onChange={(event) => {
            setStartsAt(event.target.value)
            setPreview(null)
          }}
        />

        <TextField
          label="종료 시각"
          type="datetime-local"
          name="endsAt"
          help="비우면 종료 조건 없이 유지됩니다."
          value={endsAt}
          onChange={(event) => {
            setEndsAt(event.target.value)
            setPreview(null)
          }}
        />

        <TextField
          label="제재 사유"
          name="reason"
          value={reason}
          error={errors.reason ?? null}
          onChange={(event) => setReason(event.target.value)}
        />

        <Button type="submit" variant="secondary" loading={previewing}>
          영향 미리보기
        </Button>
      </form>

      {preview !== null && (
        <>
          <StoreSanctionImpactPreviewView
            preview={preview}
            expired={previewExpired}
          />
          <Button
            type="button"
            variant="primary"
            size="lg"
            loading={submitting}
            disabled={previewExpired || awaitingReauthentication}
            onClick={handleRequestExecute}
          >
            {highRisk ? '제재 제안' : '제재 적용'}
          </Button>
        </>
      )}

      {awaitingReauthentication && attempt !== null && (
        <ReauthenticationDialog
          purpose="STORE_SANCTION"
          targetType="STORE"
          targetId={String(storeId)}
          description={`${STORE_SANCTION_TYPE_LABEL[type]} 제재를 제안합니다. 본인 확인이 필요합니다.`}
          onApproved={handleApproved}
          onCancel={() => setAwaitingReauthentication(false)}
        />
      )}
    </section>
  )
}

export function sanctionErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 사건 상세에서 처리 여부를 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '제재를 처리하지 못했습니다.'
  }
  if (error.status === 403) {
    return '이 작업을 수행할 권한이 없거나 전제조건을 충족하지 않습니다.'
  }
  if (error.status === 409) {
    return '사건 또는 매장 상태가 변경됐습니다. 최신 상태를 다시 확인하고 영향 미리보기를 새로 만들어 주세요.'
  }
  switch (error.code) {
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '같은 키로 다른 내용을 보냈습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.'
    default:
      return error.message
  }
}
