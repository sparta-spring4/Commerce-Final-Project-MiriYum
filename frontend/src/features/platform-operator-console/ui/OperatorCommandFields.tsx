import { SelectField, TextField } from '../../../shared/ui/Field'
import type { AuditReason } from '../api/operatorAccountApi'
import {
  OPERATOR_COMMAND_REASONS,
  OPERATOR_REASON_LABEL,
} from '../model/operatorLabels'

/**
 * 운영자 계정 명령이 공통으로 요구하는 입력.
 *
 * 생성·권한 교체·중지 모두 사건 ID·version 헤더와 본문 사유를 요구한다.
 * 세 폼이 같은 입력을 각자 구현하면 한 곳만 검증이 빠져도 드러나지 않는다.
 *
 * 값을 화면이 만들어 채우지 않는다. 사건 ID는 운영자가 배정받은 것이고,
 * 사유는 실제 처리 사유다. 기본값을 넣으면 감사 원장에 거짓 맥락이 남는다.
 */
export interface OperatorCommandFormState {
  caseId: string
  caseVersion: string
  reason: AuditReason
}

export const EMPTY_COMMAND_FORM: OperatorCommandFormState = {
  caseId: '',
  caseVersion: '',
  reason: 'ACCOUNT_PROVISIONING',
}

/** 공통 입력을 검증한다. 반환값이 비어 있으면 통과다. */
export function validateCommandFields(
  state: OperatorCommandFormState,
): Record<string, string> {
  const errors: Record<string, string> = {}
  if (state.caseId.trim().length === 0) {
    errors.caseId = '배정받은 사건 ID를 입력해 주세요.'
  }
  const parsed = Number(state.caseVersion)
  if (
    state.caseVersion.trim().length === 0 ||
    !Number.isInteger(parsed) ||
    parsed < 0
  ) {
    errors.caseVersion = '사건 version을 숫자로 입력해 주세요.'
  }
  return errors
}

export function OperatorCommandFields({
  state,
  errors,
  onChange,
}: {
  state: OperatorCommandFormState
  errors: Record<string, string>
  onChange: (next: OperatorCommandFormState) => void
}) {
  return (
    <fieldset className="po-fieldset po-fieldset--stack">
      <legend className="po-fieldset__legend">처리 맥락</legend>

      <TextField
        label="사건 ID"
        name="caseId"
        help="배정받은 관리 사건의 ID입니다."
        value={state.caseId}
        error={errors.caseId ?? null}
        onChange={(event) => onChange({ ...state, caseId: event.target.value })}
      />

      <TextField
        label="사건 version"
        name="caseVersion"
        inputMode="numeric"
        value={state.caseVersion}
        error={errors.caseVersion ?? null}
        onChange={(event) =>
          onChange({ ...state, caseVersion: event.target.value })
        }
      />

      <SelectField
        label="처리 사유"
        value={state.reason}
        onChange={(event) =>
          onChange({ ...state, reason: event.target.value as AuditReason })
        }
      >
        {OPERATOR_COMMAND_REASONS.map((value) => (
          <option key={value} value={value}>
            {OPERATOR_REASON_LABEL[value]}
          </option>
        ))}
      </SelectField>
    </fieldset>
  )
}
