import { useRef, useState } from 'react'
import { SelectField, TextField } from '../../../../shared/ui/Field'
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

/**
 * 같은 명령 입력의 재시도에는 같은 값을, 입력이나 대상 snapshot이 바뀐 다음
 * 제출에는 새 값을 배정한다.
 *
 * 실패할 때마다 값을 바꾸면 네트워크 결과를 모르는 동일 요청이 중복 실행될 수
 * 있고, 컴포넌트가 살아 있는 동안 계속 유지하면 다른 body를 같은 멱등 키로 보내게
 * 된다. 입력 원문(특히 임시 비밀번호)은 복제해 저장하지 않고 변경 revision만
 * 추적한다.
 */
export function useLogicalCommandAttempt<T>(
  createAttempt: () => T,
  scope = '',
) {
  const inputRevision = useRef(0)
  const [attempt, setAttempt] = useState<{
    inputRevision: number
    scope: string
    value: T
  } | null>(null)

  function markInputChanged() {
    inputRevision.current += 1
  }

  function beginAttempt(): T {
    if (
      attempt !== null &&
      attempt.inputRevision === inputRevision.current &&
      attempt.scope === scope
    ) {
      return attempt.value
    }

    const value = createAttempt()
    setAttempt({ inputRevision: inputRevision.current, scope, value })
    return value
  }

  function isAttemptCurrent(): boolean {
    return (
      attempt !== null &&
      attempt.inputRevision === inputRevision.current &&
      attempt.scope === scope
    )
  }

  function clearAttempt() {
    setAttempt(null)
  }

  return {
    attempt: attempt?.value ?? null,
    beginAttempt,
    clearAttempt,
    isAttemptCurrent,
    markInputChanged,
  }
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
