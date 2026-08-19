import { useState } from 'react'
import { isApiError, isNetworkError } from '../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../shared/api/envelope'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { Alert } from '../../../../shared/ui/Feedback'
import { usePlatformOperatorAuth } from '../../../account/platform-operator/auth'
import {
  replaceOperatorAuthority,
  type GrantablePermission,
  type GrantableRole,
  type OperatorAccountDetail,
} from '../api/operatorAccountApi'
import {
  GRANTABLE_PERMISSIONS,
  GRANTABLE_ROLES,
  OPERATOR_PERMISSION_LABEL,
  OPERATOR_ROLE_LABEL,
} from '../model/operatorLabels'
import {
  EMPTY_COMMAND_FORM,
  OperatorCommandFields,
  useLogicalCommandAttempt,
  validateCommandFields,
  type OperatorCommandFormState,
} from './OperatorCommandFields'
import { ReauthenticationDialog } from './ReauthenticationDialog'
import './page.css'

/**
 * 역할·직접 권한 전체 교체.
 *
 * **부분 갱신이 아니다.** 체크를 해제한 항목은 제거된다. 화면 문구를 "추가"가
 * 아니라 "교체"로 두고, 제출 전에 현재 값과의 차이를 보여 준다.
 *
 * 계약이 `NonSuperAdminRole`·`NonCorePermission`만 받는다. `SUPER_ADMIN`과
 * 핵심 권한(운영자 생성·중지·영구정지 승인·비상 승인)은 이 API로 부여할 수
 * 없으므로 선택지에 넣지 않는다. 대상이 이미 그 권한을 가졌다면 아래 "변경할
 * 수 없는 권한"에 따로 표시한다.
 */
export function OperatorAuthorityForm({
  account,
  onReplaced,
}: {
  account: OperatorAccountDetail
  onReplaced: () => void
}) {
  const { apiClient } = usePlatformOperatorAuth()

  // 현재 값을 초깃값으로 둔다. 빈 폼에서 시작하면 저장 순간 전부 회수된다.
  const [roles, setRoles] = useState<GrantableRole[]>(() =>
    GRANTABLE_ROLES.filter((role) =>
      (account.roles as readonly string[]).includes(role),
    ),
  )
  const [permissions, setPermissions] = useState<GrantablePermission[]>(() =>
    GRANTABLE_PERMISSIONS.filter((permission) =>
      (account.directPermissions as readonly string[]).includes(permission),
    ),
  )
  const [command, setCommand] =
    useState<OperatorCommandFormState>(EMPTY_COMMAND_FORM)
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
    markInputChanged,
  } = useLogicalCommandAttempt(
    () => ({ idempotencyKey: createIdempotencyKey() }),
    `${account.operatorId}:${account.authorityVersion}`,
  )

  /** 이 API로 바꿀 수 없는 보유 권한. 표시만 하고 폼에 넣지 않는다. */
  const unmanagedRoles = account.roles.filter(
    (role) => !(GRANTABLE_ROLES as readonly string[]).includes(role),
  )
  const unmanagedDirectPermissions = account.directPermissions.filter(
    (permission) =>
      !(GRANTABLE_PERMISSIONS as readonly string[]).includes(permission),
  )

  function toggleRole(role: GrantableRole) {
    markInputChanged()
    setRoles((current) =>
      current.includes(role)
        ? current.filter((item) => item !== role)
        : [...current, role],
    )
  }

  function togglePermission(permission: GrantablePermission) {
    markInputChanged()
    setPermissions((current) =>
      current.includes(permission)
        ? current.filter((item) => item !== permission)
        : [...current, permission],
    )
  }

  function handleRequestReauthentication(event: React.FormEvent) {
    event.preventDefault()
    setResult(null)
    const nextErrors = validateCommandFields(command)
    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0) {
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
      await replaceOperatorAuthority(apiClient, {
        operatorId: account.operatorId,
        context: {
          caseId: command.caseId.trim(),
          caseVersion: Number(command.caseVersion),
        },
        reauthenticationApproval: approval,
        idempotencyKey: attempt.idempotencyKey,
        body: {
          roles,
          directPermissions: permissions,
          reason: command.reason,
        },
      })
      setResult('권한을 교체했습니다. 최신 상태를 다시 읽었습니다.')
      clearAttempt()
    } catch (error) {
      setFormError(commandErrorMessage(error))
    } finally {
      setSubmitting(false)
      // 성공이든 충돌이든 서버 상태를 다시 읽는다. authorityVersion이 바뀐다.
      onReplaced()
    }
  }

  return (
    <section aria-labelledby="authority-form-heading">
      <h2 className="po-section__title" id="authority-form-heading">
        역할·직접 권한 변경
      </h2>

      <Alert tone="warning" title="전체 교체입니다.">
        선택하지 않은 역할과 직접 권한은 <strong>제거됩니다.</strong> 현재 값이
        미리 선택돼 있으니 바꿀 항목만 조정해 주세요. 처리에는 본인 확인이
        필요하며 감사 기록에 남습니다.
      </Alert>

      {result !== null && <Alert tone="info" title={result} />}
      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="po-form"
        onSubmit={handleRequestReauthentication}
        aria-label="역할·직접 권한 변경"
        inert={awaitingReauthentication}
        noValidate
      >
        <fieldset className="po-fieldset">
          <legend className="po-fieldset__legend">역할</legend>
          {GRANTABLE_ROLES.map((role) => (
            <label key={role} className="po-checkbox">
              <input
                type="checkbox"
                checked={roles.includes(role)}
                onChange={() => toggleRole(role)}
              />
              {OPERATOR_ROLE_LABEL[role]}
            </label>
          ))}
        </fieldset>

        <fieldset className="po-fieldset">
          <legend className="po-fieldset__legend">직접 권한</legend>
          {GRANTABLE_PERMISSIONS.map((permission) => (
            <label key={permission} className="po-checkbox">
              <input
                type="checkbox"
                checked={permissions.includes(permission)}
                onChange={() => togglePermission(permission)}
              />
              {OPERATOR_PERMISSION_LABEL[permission]}
            </label>
          ))}
        </fieldset>

        {(unmanagedRoles.length > 0 ||
          unmanagedDirectPermissions.length > 0) && (
          <Alert tone="info" title="이 화면에서 바꿀 수 없는 권한이 있습니다.">
            계약이 슈퍼관리자 역할과 핵심 권한을 이 API로 부여·회수하지 못하게
            합니다. 아래 항목은 그대로 유지됩니다.
            <ul className="po-list">
              {unmanagedRoles.map((role) => (
                <li key={role}>역할 · {OPERATOR_ROLE_LABEL[role]}</li>
              ))}
              {unmanagedDirectPermissions.map((permission) => (
                <li key={permission}>
                  권한 · {OPERATOR_PERMISSION_LABEL[permission]}
                </li>
              ))}
            </ul>
          </Alert>
        )}

        <OperatorCommandFields
          state={command}
          errors={errors}
          onChange={(next) => {
            markInputChanged()
            setCommand(next)
          }}
        />

        <Button
          type="submit"
          variant="primary"
          size="lg"
          loading={submitting}
          disabled={awaitingReauthentication}
        >
          권한 교체
        </Button>
      </form>

      {awaitingReauthentication && attempt !== null && (
        <ReauthenticationDialog
          purpose="OPERATOR_AUTHORITY_CHANGE"
          targetType="PLATFORM_OPERATOR_ACCOUNT"
          targetId={account.operatorId}
          description={`${account.displayName}의 역할·권한을 교체합니다. 본인 확인이 필요합니다.`}
          onApproved={handleApproved}
          onCancel={() => setAwaitingReauthentication(false)}
        />
      )}
    </section>
  )
}

function commandErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 처리 여부를 상태에서 다시 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '권한을 교체하지 못했습니다.'
  }
  if (error.status === 403) {
    return '이 작업을 수행할 권한이 없거나 전제조건을 충족하지 않습니다.'
  }
  switch (error.code) {
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '같은 키로 다른 내용을 보냈습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.'
    case CommonErrorCode.CONCURRENT_MODIFICATION:
      return '대상 상태가 변경됐습니다. 최신 정보를 확인한 뒤 다시 시도해 주세요.'
    default:
      return error.message
  }
}
