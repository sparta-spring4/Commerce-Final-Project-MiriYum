import { useState } from 'react'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import type { AuditReason, AuditReviewContext } from '../api/auditApi'
import { REASON_LABEL } from './auditLabels'
import './page.css'

/**
 * 감사 조회 맥락 입력.
 *
 * 검색과 상세가 모두 이 폼을 먼저 지난다. 감사 조회는 배정받은 `AUDIT_REVIEW`
 * 사건과 사유 코드를 헤더로 함께 보내야 하고, 허용된 조회와 거부된 조회가
 * 모두 원장에 append된다. 값을 화면이 만들어 채우면 거짓 사유가 기록된다.
 *
 * 맥락을 전역에 저장해 화면 간에 재사용하지 않는다. 사유가 다른 조회에
 * 같은 사유가 붙는다.
 *
 * 조회 결과 화면에 입력을 섞지 않고 앞에 둔다. 뒤에 두면 사유가 빈 채로
 * 목록이 먼저 뜨고, 그 상태에서 나간 요청이 서버에 거부로 기록된다.
 */
export function AuditReviewContextForm({
  heading,
  subtitle,
  onSubmit,
}: {
  heading: string
  subtitle: string
  onSubmit: (context: AuditReviewContext) => void
}) {
  const [caseId, setCaseId] = useState('')
  const [caseVersion, setCaseVersion] = useState('')
  const [reasonCode, setReasonCode] = useState<AuditReason>('AUDIT_VERIFICATION')
  const [errors, setErrors] = useState<Record<string, string>>({})

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    const nextErrors: Record<string, string> = {}
    if (caseId.trim().length === 0) {
      nextErrors.caseId = '배정받은 감사 사건 ID를 입력해 주세요.'
    }
    const parsedVersion = Number(caseVersion)
    if (
      caseVersion.trim().length === 0 ||
      !Number.isInteger(parsedVersion) ||
      parsedVersion < 0
    ) {
      nextErrors.caseVersion = '사건 version을 숫자로 입력해 주세요.'
    }
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      return
    }
    onSubmit({ caseId: caseId.trim(), caseVersion: parsedVersion, reasonCode })
  }

  return (
    <section aria-labelledby="audit-context-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="audit-context-heading">
            {heading}
          </h1>
          <p className="po-page__subtitle">{subtitle}</p>
        </div>
      </header>

      <Alert tone="info" title="이 조회는 감사 기록에 남습니다.">
        허용된 조회와 거부된 조회가 모두 기록됩니다. 배정받은 사건과 실제 사유를
        입력해 주세요. 권한만으로는 조회할 수 없으며 사건 배정이 필요합니다.
      </Alert>

      <form
        className="po-form"
        onSubmit={handleSubmit}
        aria-label="감사 조회 맥락"
        noValidate
      >
        <TextField
          label="감사 사건 ID"
          name="caseId"
          value={caseId}
          error={errors.caseId ?? null}
          onChange={(event) => setCaseId(event.target.value)}
        />
        <TextField
          label="사건 version"
          name="caseVersion"
          inputMode="numeric"
          value={caseVersion}
          error={errors.caseVersion ?? null}
          onChange={(event) => setCaseVersion(event.target.value)}
        />
        <SelectField
          label="조회 사유"
          value={reasonCode}
          onChange={(event) => setReasonCode(event.target.value as AuditReason)}
        >
          {Object.entries(REASON_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>

        <Button type="submit" variant="primary" size="lg">
          조회 시작
        </Button>
      </form>
    </section>
  )
}
