import { PLATFORM_OPERATOR_PATHS } from '../../../../app/routes/paths/platformOperatorPaths'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { isApiError, isNetworkError } from '../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../shared/api/envelope'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { PasswordField, TextField } from '../../../../shared/ui/Field'
import { Alert, Loading } from '../../../../shared/ui/Feedback'
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../../account/platform-operator/auth'
import {
  createOperatorAccount,
  type GrantablePermission,
  type GrantableRole,
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
import { operatorDetailPath } from '../model/paths'
import './page.css'

/**
 * 운영자 계정 생성.
 *
 * 공개 가입이 없고 슈퍼관리자가 발급한다. 계약이 `SUPER_ADMIN` 역할과 핵심
 * 권한을 부여할 수 없게 하므로 선택지에도 넣지 않는다.
 *
 * `provisioningId`는 한 논리 발급 시도를 식별하는 UUID다. 같은 입력 재시도에는
 * 유지하고, 실패 뒤 입력이 바뀌면 멱등 키와 함께 갱신한다.
 *
 * 임시 비밀번호는 요청에만 담고 응답에 오지 않는다. 발급 결과 화면에서 다시
 * 보여 주지 않으며 어디에도 저장하지 않는다.
 */
export function OperatorCreatePage() {
  const { apiClient, capabilities } = usePlatformOperatorAuth()
  const navigate = useNavigate()

  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [temporaryPassword, setTemporaryPassword] = useState('')
  const [roles, setRoles] = useState<GrantableRole[]>([])
  const [permissions, setPermissions] = useState<GrantablePermission[]>([])
  const [command, setCommand] = useState<OperatorCommandFormState>({
    ...EMPTY_COMMAND_FORM,
    reason: 'ACCOUNT_PROVISIONING',
  })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [awaitingReauthentication, setAwaitingReauthentication] =
    useState(false)

  const {
    attempt,
    beginAttempt,
    clearAttempt,
    isAttemptCurrent,
    markInputChanged,
  } = useLogicalCommandAttempt(() => ({
    provisioningId: crypto.randomUUID(),
    idempotencyKey: createIdempotencyKey(),
  }))

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
    const nextErrors = validateCommandFields(command)
    if (email.trim().length === 0) {
      nextErrors.email = '이메일을 입력해 주세요.'
    }
    if (displayName.trim().length === 0) {
      nextErrors.displayName = '표시명을 입력해 주세요.'
    }
    if (temporaryPassword.length === 0) {
      nextErrors.temporaryPassword = '임시 비밀번호를 입력해 주세요.'
    }
    // 아무 권한도 없는 계정은 만들 수 있지만 의도한 것인지 확인시킨다.
    if (roles.length === 0 && permissions.length === 0) {
      nextErrors.authority = '역할 또는 직접 권한을 하나 이상 선택해 주세요.'
    }
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
      const created = await createOperatorAccount(apiClient, {
        context: {
          caseId: command.caseId.trim(),
          caseVersion: Number(command.caseVersion),
        },
        reauthenticationApproval: approval,
        idempotencyKey: attempt.idempotencyKey,
        body: {
          provisioningId: attempt.provisioningId,
          email: email.trim(),
          displayName: displayName.trim(),
          temporaryPassword,
          roles,
          directPermissions: permissions,
          reason: command.reason,
        },
      })
      // 평문 임시 비밀번호를 즉시 비운다. 결과 화면에서 다시 보여 주지 않는다.
      setTemporaryPassword('')
      clearAttempt()
      void navigate(operatorDetailPath(created.operatorId), { replace: true })
    } catch (error) {
      setFormError(createErrorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  const createDecision = decideCapability(capabilities, 'OPERATOR_CREATE')
  if (createDecision === 'undetermined') {
    return <Loading label="운영자 생성 권한을 확인하는 중입니다." />
  }
  if (createDecision === 'denied') {
    return (
      <Alert tone="warning" title="이 업무를 수행할 권한이 없습니다.">
        운영자 등록에는 <code>OPERATOR_CREATE</code> 권한이 필요합니다. 담당
        업무에 이 권한이 필요하면 슈퍼관리자에게 요청해 주세요.
      </Alert>
    )
  }

  return (
    <section aria-labelledby="operator-create-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="operator-create-heading">
            운영자 등록
          </h1>
          <p className="po-page__subtitle">
            <Link
              to={PLATFORM_OPERATOR_PATHS.operators}
              className="po-table__link"
            >
              운영자 목록으로
            </Link>
          </p>
        </div>
      </header>

      <Alert tone="warning" title="고위험 작업입니다.">
        새 운영자 계정을 발급합니다. 처리에는 본인 확인이 필요하며 감사 기록에
        남습니다. 임시 비밀번호는 발급 후 화면에 다시 표시되지 않으므로 안전한
        경로로 전달해 주세요.
      </Alert>

      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="po-form"
        onSubmit={handleRequestReauthentication}
        aria-label="운영자 등록"
        inert={awaitingReauthentication}
        noValidate
      >
        <TextField
          label="이메일"
          type="email"
          name="email"
          autoComplete="off"
          value={email}
          error={errors.email ?? null}
          onChange={(event) => {
            markInputChanged()
            setEmail(event.target.value)
          }}
        />

        <TextField
          label="표시명"
          name="displayName"
          value={displayName}
          error={errors.displayName ?? null}
          onChange={(event) => {
            markInputChanged()
            setDisplayName(event.target.value)
          }}
        />

        <PasswordField
          label="임시 비밀번호"
          name="temporaryPassword"
          autoComplete="new-password"
          help="8~64자이며 대문자·소문자·숫자·특수문자 중 3종 이상을 포함합니다."
          value={temporaryPassword}
          error={errors.temporaryPassword ?? null}
          onChange={(event) => {
            markInputChanged()
            setTemporaryPassword(event.target.value)
          }}
        />

        <fieldset className="po-fieldset">
          <legend className="po-fieldset__legend">역할</legend>
          {errors.authority !== undefined && (
            <p className="po-fieldset__error" role="alert">
              {errors.authority}
            </p>
          )}
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
          운영자 등록
        </Button>
      </form>

      {awaitingReauthentication && attempt !== null && (
        <ReauthenticationDialog
          purpose="OPERATOR_CREATION"
          targetType="PLATFORM_OPERATOR_ACCOUNT"
          targetId={attempt.provisioningId}
          description={`${displayName.trim()} 운영자 계정을 발급합니다. 본인 확인이 필요합니다.`}
          onApproved={handleApproved}
          onCancel={() => setAwaitingReauthentication(false)}
        />
      )}
    </section>
  )
}

function createErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 목록에서 발급 여부를 확인한 뒤 다시 시도해 주세요.'
  }
  if (!isApiError(error)) {
    return '운영자를 등록하지 못했습니다.'
  }
  if (error.status === 403) {
    return '이 작업을 수행할 권한이 없거나 전제조건을 충족하지 않습니다.'
  }
  switch (error.code) {
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '같은 키로 다른 내용을 보냈습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.'
    case CommonErrorCode.CONCURRENT_MODIFICATION:
      return '동시 요청이 충돌했습니다. 목록에서 발급 여부를 확인해 주세요.'
    default:
      // 이메일 중복·비밀번호 정책 위반은 서버 문구가 가장 정확하다.
      return error.message
  }
}
