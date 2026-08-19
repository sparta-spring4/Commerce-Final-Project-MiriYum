import { useState } from 'react'
import { Button } from '../../../../shared/ui/Button'
import { SelectField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import type { AuditReason } from '../api/operatorAccountApi'
import { OPERATOR_REASON_LABEL } from '../model/operatorLabels'
import './page.css'

/**
 * 조회 사유 코드 선입력 게이트.
 *
 * 매장 조회는 `X-Admin-Reason-Code`를 필수 헤더로 요구하고, 그 조회 자체가
 * 감사 원장에 기록된다. 사유 없이 화면에 들어오는 것만으로 조회가 나가면
 * 운영자가 고르지 않은 사유가 기록되거나 거부가 쌓인다.
 *
 * 그래서 사유를 고르기 전에는 어떤 조회도 실행하지 않는다. 기본값을 넣어
 * 자동 통과시키지 않는 것이 이 컴포넌트의 목적이다.
 */

/** 매장 조회에서 고를 수 있는 사유. 계약 enum 중 이 업무에 해당하는 값만 둔다. */
const STORE_REASONS: readonly AuditReason[] = [
  'SECURITY_RESPONSE',
  'AUDIT_VERIFICATION',
  'STORE_ENFORCEMENT',
]

export function AdminReasonGate({
  heading,
  subtitle,
  onSubmit,
}: {
  heading: string
  subtitle: string
  onSubmit: (reasonCode: AuditReason) => void
}) {
  const [reasonCode, setReasonCode] = useState<AuditReason>('AUDIT_VERIFICATION')

  return (
    <section aria-labelledby="reason-gate-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="reason-gate-heading">
            {heading}
          </h1>
          <p className="po-page__subtitle">{subtitle}</p>
        </div>
      </header>

      <Alert tone="info" title="이 조회는 감사 기록에 남습니다.">
        조회 사유를 먼저 선택해 주세요. 사유를 고르기 전에는 어떤 조회도
        실행되지 않습니다.
      </Alert>

      <form
        className="po-form"
        aria-label="조회 사유 선택"
        onSubmit={(event) => {
          event.preventDefault()
          onSubmit(reasonCode)
        }}
        noValidate
      >
        <SelectField
          label="조회 사유"
          value={reasonCode}
          onChange={(event) =>
            setReasonCode(event.target.value as AuditReason)
          }
        >
          {STORE_REASONS.map((value) => (
            <option key={value} value={value}>
              {OPERATOR_REASON_LABEL[value]}
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
