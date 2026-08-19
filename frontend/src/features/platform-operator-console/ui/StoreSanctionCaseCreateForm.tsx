import { useState } from 'react'
import { useNavigate } from 'react-router'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { usePlatformOperatorAuth } from '../../platform-operator-auth'
import type { AuditReason } from '../api/operatorAccountApi'
import { createStoreSanctionCase } from '../api/storeAdminApi'
import { storeSanctionCasePath } from '../model/paths'
import './page.css'

/** 계약이 증거 참조를 1~20개로 제한한다. */
const MIN_EVIDENCE = 1
const MAX_EVIDENCE = 20

/**
 * 제재 사건 생성.
 *
 * 생성 결과는 `SUBMITTED`이고 **생성자에게 자동 배정되지 않는다.** 화면이
 * "담당자로 지정됐다"고 표현하면 실제로는 미배정인 사건을 처리 중으로 오인한다.
 * 자기 배정은 사건 상세에서 별도로 한다.
 *
 * 사건 생성 자체는 제재가 아니다. 재인증을 요구하지 않는 이유이며, 화면
 * 문구도 "제재를 적용한다"가 아니라 "사건을 접수한다"로 둔다.
 */
export function StoreSanctionCaseCreateForm({
  storeId,
  reasonCode,
  onCreated,
}: {
  storeId: number
  reasonCode: AuditReason
  onCreated: () => void
}) {
  const { apiClient } = usePlatformOperatorAuth()
  const navigate = useNavigate()

  const [violationType, setViolationType] = useState('')
  const [policyVersion, setPolicyVersion] = useState('')
  const [evidenceText, setEvidenceText] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [idempotencyKey, setIdempotencyKey] = useState(() =>
    createIdempotencyKey(),
  )

  /** 한 줄에 하나씩 입력받는다. 빈 줄은 버린다. */
  const evidenceReferences = evidenceText
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    const nextErrors: Record<string, string> = {}
    if (violationType.trim().length === 0) {
      nextErrors.violationType = '위반 유형을 입력해 주세요.'
    }
    if (policyVersion.trim().length === 0) {
      nextErrors.policyVersion = '정책 버전을 입력해 주세요.'
    }
    if (evidenceReferences.length < MIN_EVIDENCE) {
      nextErrors.evidence = '증거 참조를 한 개 이상 입력해 주세요.'
    } else if (evidenceReferences.length > MAX_EVIDENCE) {
      nextErrors.evidence = `증거 참조는 최대 ${MAX_EVIDENCE}개까지입니다.`
    }
    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    setSubmitting(true)
    try {
      const created = await createStoreSanctionCase(apiClient, {
        storeId,
        reasonCode,
        idempotencyKey,
        body: {
          violationType: violationType.trim(),
          evidenceReferences,
          policyVersion: policyVersion.trim(),
        },
      })
      setIdempotencyKey(createIdempotencyKey())
      onCreated()
      void navigate(storeSanctionCasePath(storeId, created.caseId))
    } catch (error) {
      setFormError(createCaseErrorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section aria-labelledby="case-create-heading">
      <h2 className="po-section__title" id="case-create-heading">
        제재 사건 접수
      </h2>

      <Alert tone="info" title="사건 접수는 제재 적용이 아닙니다.">
        접수한 사건은 <strong>접수(SUBMITTED)</strong> 상태로 시작하며 담당자가
        지정되지 않습니다. 처리하려면 사건 상세에서 자기 배정을 먼저 해야 합니다.
      </Alert>

      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="po-form"
        onSubmit={handleSubmit}
        aria-label="제재 사건 접수"
        noValidate
      >
        <TextField
          label="위반 유형"
          name="violationType"
          value={violationType}
          error={errors.violationType ?? null}
          onChange={(event) => setViolationType(event.target.value)}
        />

        <TextField
          label="정책 버전"
          name="policyVersion"
          value={policyVersion}
          error={errors.policyVersion ?? null}
          onChange={(event) => setPolicyVersion(event.target.value)}
        />

        <label className="po-textarea">
          <span className="po-textarea__label">증거 참조</span>
          <span className="po-textarea__hint">
            한 줄에 하나씩 입력합니다. 1~{MAX_EVIDENCE}개.
          </span>
          <textarea
            className="po-textarea__control"
            name="evidenceReferences"
            rows={4}
            value={evidenceText}
            onChange={(event) => setEvidenceText(event.target.value)}
          />
          {errors.evidence !== undefined && (
            <span className="po-textarea__error" role="alert">
              {errors.evidence}
            </span>
          )}
        </label>

        <p className="po-form__notice">
          현재 {evidenceReferences.length}개 입력됨.
        </p>

        <Button
          type="submit"
          variant="primary"
          size="lg"
          loading={submitting}
        >
          사건 접수
        </Button>
      </form>
    </section>
  )
}

function createCaseErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 매장 상세에서 접수 여부를 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '사건을 접수하지 못했습니다.'
  }
  if (error.status === 403) {
    return '제재 사건을 접수할 권한이 없습니다.'
  }
  switch (error.code) {
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '같은 키로 다른 내용을 보냈습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.'
    case CommonErrorCode.CONCURRENT_MODIFICATION:
      return '동시 요청이 충돌했습니다. 매장 상세에서 접수 여부를 확인해 주세요.'
    default:
      return error.message
  }
}
