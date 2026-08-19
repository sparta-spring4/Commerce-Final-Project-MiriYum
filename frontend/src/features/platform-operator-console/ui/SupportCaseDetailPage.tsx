import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ROUTES } from '../../../app/routes'
import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { SelectField, TextField } from '../../../shared/ui/Field'
import { Alert, EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { AuthErrorCode } from '../../auth/model/authErrors'
import {
  decideCapability,
  usePlatformOperatorAuth,
  type PlatformOperatorPermission,
} from '../../platform-operator-auth'
import {
  assignSupportCase,
  decideSupportCase,
  fetchSupportCase,
  supportCaseQueryKeys,
  type CaseDecisionRequest,
  type RestrictedFeature,
  type SanctionLevel,
  type SupportCase,
} from '../api/memberSupportApi'
import { accountTargetType } from '../api/reauthenticationApi'
import { memberDetailPath } from '../model/paths'
import { OperatorCapabilityGate } from './OperatorAccessDenied'
import { useLogicalCommandAttempt } from './OperatorCommandFields'
import {
  CASE_STATUS_LABEL,
  CASE_STATUS_TONE,
  CASE_TYPE_LABEL,
} from './SupportCaseListPage'
import { ReauthenticationDialog } from './ReauthenticationDialog'
import './page.css'

type Decision = CaseDecisionRequest['decision']

/**
 * 사건 유형별로 허용되는 결정.
 *
 * 계약의 `CaseDecisionRequest.decision`은 다섯 값을 모두 담지만, 복구 사건에
 * 제재 유지·경감을 보내는 것은 뜻이 통하지 않는다. 서버가 거절할 조합을 화면에
 * 그리지 않는다. 계약이 유형별 제한을 스키마로 표현하지 않으므로 여기서 좁힌다.
 */
const DECISIONS_BY_CASE_TYPE: Record<
  SupportCase['caseType'],
  readonly Decision[]
> = {
  ACCOUNT_RECOVERY: ['APPROVE', 'REJECT'],
  ACCOUNT_APPEAL: ['UPHOLD', 'REDUCE', 'CANCEL'],
}

const COMMAND_PERMISSION_BY_CASE_TYPE: Record<
  SupportCase['caseType'],
  PlatformOperatorPermission
> = {
  ACCOUNT_RECOVERY: 'MEMBER_RECOVERY',
  ACCOUNT_APPEAL: 'ACCOUNT_APPEAL_REVIEW',
}

const DECISION_LABEL: Record<Decision, string> = {
  APPROVE: '승인',
  REJECT: '반려',
  UPHOLD: '제재 유지',
  REDUCE: '제재 경감',
  CANCEL: '취소',
}

const REDUCED_LEVEL_LABEL: Record<
  Exclude<SanctionLevel, 'PERMANENT_SUSPENSION'>,
  string
> = {
  WARNING: '경고',
  FEATURE_RESTRICTION: '기능 제한',
  TEMPORARY_SUSPENSION: '기간 정지',
}

const RESTRICTED_FEATURE_LABEL: Record<RestrictedFeature, string> = {
  RESERVATION: '예약',
  WAITING: '웨이팅',
  PICKUP: '픽업',
  STORE_OPERATION: '매장 운영',
  MENU_OPERATION: '메뉴 운영',
}

/** 결정을 내릴 수 있는 상태. 종결된 사건에는 명령을 보내지 않는다. */
const OPEN_STATUSES = new Set<SupportCase['status']>(['SUBMITTED', 'ASSIGNED'])

const REASON_CODE_PATTERN = /^[A-Z0-9_]+$/

/**
 * 회원지원 사건 상세.
 *
 * 배정과 결정이 서로 다른 명령이다. 배정은 version만 요구하고, 결정은 version과
 * 재인증 승인과 멱등 키를 모두 요구한다. 두 명령을 한 버튼으로 합치지 않는다.
 *
 * 어느 쪽이든 성공을 낙관 확정하지 않고 서버에서 사건을 다시 읽는다.
 * version이 바뀌므로 재조회 없이는 다음 명령이 409로 거절된다.
 */
export function SupportCaseDetailPage() {
  return (
    <OperatorCapabilityGate permission="MEMBER_READ_MINIMAL">
      <SupportCaseDetailContent />
    </OperatorCapabilityGate>
  )
}

function SupportCaseDetailContent() {
  const { apiClient } = usePlatformOperatorAuth()
  const params = useParams<{ caseId: string }>()
  const caseId = params.caseId

  const caseQuery = useQuery({
    queryKey: supportCaseQueryKeys.detail(caseId ?? ''),
    queryFn: ({ signal }) => fetchSupportCase(apiClient, caseId!, signal),
    enabled: caseId !== undefined,
  })

  if (caseId === undefined) {
    return (
      <EmptyState
        title="잘못된 주소입니다."
        description="사건 목록에서 다시 선택해 주세요."
      />
    )
  }

  return (
    <section aria-labelledby="support-case-detail-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="support-case-detail-heading">
            사건 상세
          </h1>
          <p className="po-page__subtitle">
            <Link
              to={ROUTES.platformOperatorSupportCases}
              className="po-table__link"
            >
              사건 목록으로
            </Link>
          </p>
        </div>
      </header>

      {caseQuery.isPending && <Loading label="사건을 불러오는 중입니다." />}

      {caseQuery.isError && (
        <ErrorState
          error={caseQuery.error}
          onRetry={() => void caseQuery.refetch()}
        />
      )}

      {caseQuery.data !== undefined && (
        <SupportCaseBody
          supportCase={caseQuery.data}
          onChanged={() => void caseQuery.refetch()}
        />
      )}
    </section>
  )
}

function SupportCaseBody({
  supportCase,
  onChanged,
}: {
  supportCase: SupportCase
  onChanged: () => void
}) {
  const { apiClient, capabilities } = usePlatformOperatorAuth()
  const [assigning, setAssigning] = useState(false)
  const [assignError, setAssignError] = useState<string | null>(null)

  const isOpen = OPEN_STATUSES.has(supportCase.status)
  const commandPermission =
    COMMAND_PERMISSION_BY_CASE_TYPE[supportCase.caseType]
  const canHandle =
    decideCapability(capabilities, commandPermission) === 'allowed'

  async function handleAssign() {
    setAssigning(true)
    setAssignError(null)
    try {
      await assignSupportCase(
        apiClient,
        supportCase.caseId,
        supportCase.version,
      )
    } catch (error) {
      setAssignError(commandErrorMessage(error))
    } finally {
      setAssigning(false)
      // 성공이든 충돌이든 서버 상태를 다시 읽는다.
      onChanged()
    }
  }

  return (
    <>
      <dl className="po-detail">
        <div className="po-detail__row">
          <dt>사건</dt>
          <dd>{supportCase.caseId}</dd>
        </div>
        <div className="po-detail__row">
          <dt>유형</dt>
          <dd>{CASE_TYPE_LABEL[supportCase.caseType]}</dd>
        </div>
        <div className="po-detail__row">
          <dt>대상</dt>
          <dd>
            <Link
              to={memberDetailPath(
                supportCase.accountType,
                supportCase.accountId,
              )}
              className="po-table__link"
            >
              {supportCase.accountType} · {supportCase.accountId}
            </Link>
          </dd>
        </div>
        <div className="po-detail__row">
          <dt>상태</dt>
          <dd>
            <Badge tone={CASE_STATUS_TONE[supportCase.status]}>
              {CASE_STATUS_LABEL[supportCase.status]}
            </Badge>
          </dd>
        </div>
        <div className="po-detail__row">
          <dt>담당</dt>
          <dd>{supportCase.assignedOperatorId ?? '미배정'}</dd>
        </div>
        <div className="po-detail__row">
          <dt>version</dt>
          <dd>{supportCase.version}</dd>
        </div>
        <div className="po-detail__row">
          <dt>접수</dt>
          <dd>{formatTimestamp(supportCase.submittedAt)}</dd>
        </div>
        {supportCase.decidedAt != null && (
          <div className="po-detail__row">
            <dt>결정</dt>
            <dd>{formatTimestamp(supportCase.decidedAt)}</dd>
          </div>
        )}
      </dl>

      {!isOpen ? (
        <Alert tone="info" title="이미 종결된 사건입니다.">
          종결된 사건에는 배정과 결정을 보낼 수 없습니다.
        </Alert>
      ) : !canHandle ? (
        <Alert tone="warning" title="이 사건을 처리할 권한이 없습니다.">
          배정과 결정에는 <code>{commandPermission}</code> 권한이 필요합니다.
        </Alert>
      ) : (
        <>
          <section aria-labelledby="assign-heading">
            <h2 className="po-section__title" id="assign-heading">
              담당 배정
            </h2>
            {assignError !== null && (
              <Alert tone="error" title={assignError} />
            )}
            <p className="po-form__notice">
              version {supportCase.version} 기준으로 자기 자신에게 배정합니다.
              다른 운영자가 먼저 배정했으면 서버가 거절합니다.
            </p>
            <Button
              type="button"
              variant="secondary"
              loading={assigning}
              onClick={handleAssign}
            >
              나에게 배정
            </Button>
          </section>

          <DecisionForm supportCase={supportCase} onDecided={onChanged} />
        </>
      )}
    </>
  )
}

function DecisionForm({
  supportCase,
  onDecided,
}: {
  supportCase: SupportCase
  onDecided: () => void
}) {
  const { apiClient } = usePlatformOperatorAuth()
  const allowed = DECISIONS_BY_CASE_TYPE[supportCase.caseType]
  const [decision, setDecision] = useState<Decision>(allowed[0])
  const [reasonCode, setReasonCode] = useState('')
  const [reducedLevel, setReducedLevel] =
    useState<Exclude<SanctionLevel, 'PERMANENT_SUSPENSION'>>('WARNING')
  const [restrictedFeatures, setRestrictedFeatures] = useState<
    RestrictedFeature[]
  >([])
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
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
      caseId: supportCase.caseId,
      version: supportCase.version,
      decision,
      reasonCode,
      reducedLevel,
      restrictedFeatures,
    }),
  )

  function handleRequestReauthentication(event: React.FormEvent) {
    event.preventDefault()
    const errors: Record<string, string> = {}
    if (reasonCode.length === 0) {
      errors.reasonCode = '사유 코드를 입력해 주세요.'
    } else if (!REASON_CODE_PATTERN.test(reasonCode)) {
      errors.reasonCode = '대문자·숫자·밑줄만 사용할 수 있습니다.'
    }
    if (
      decision === 'REDUCE' &&
      reducedLevel === 'FEATURE_RESTRICTION' &&
      restrictedFeatures.length === 0
    ) {
      errors.restrictedFeatures = '기능 제한은 하나 이상의 기능을 선택해야 합니다.'
    }
    setFieldErrors(errors)
    setFormError(null)
    if (Object.keys(errors).length > 0) {
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
      const body: CaseDecisionRequest =
        decision === 'REDUCE'
          ? {
              decision,
              reasonCode,
              reducedLevel,
              restrictedFeatures:
                reducedLevel === 'FEATURE_RESTRICTION'
                  ? restrictedFeatures
                  : [],
            }
          : { decision, reasonCode }
      await decideSupportCase(apiClient, {
        caseId: supportCase.caseId,
        version: supportCase.version,
        reauthenticationApproval: approval,
        idempotencyKey: attempt.idempotencyKey,
        body,
      })
      clearAttempt()
      setReasonCode('')
      setReducedLevel('WARNING')
      setRestrictedFeatures([])
    } catch (error) {
      setFormError(commandErrorMessage(error))
    } finally {
      setSubmitting(false)
      // 결정 성공을 화면이 확정하지 않는다. 서버가 준 최종 상태를 다시 읽는다.
      onDecided()
    }
  }

  return (
    <section aria-labelledby="decision-heading">
      <h2 className="po-section__title" id="decision-heading">
        사건 결정
      </h2>

      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="po-form"
        onSubmit={handleRequestReauthentication}
        aria-label="사건 결정"
        inert={awaitingReauthentication}
        noValidate
      >
        <SelectField
          label="결정"
          value={decision}
          onChange={(event) => {
            const nextDecision = event.target.value as Decision
            setDecision(nextDecision)
            if (nextDecision !== 'REDUCE') {
              setRestrictedFeatures([])
            }
          }}
        >
          {allowed.map((value) => (
            <option key={value} value={value}>
              {DECISION_LABEL[value]}
            </option>
          ))}
        </SelectField>

        {decision === 'REDUCE' && (
          <>
            <SelectField
              label="경감 수준"
              value={reducedLevel}
              onChange={(event) => {
                const nextLevel = event.target.value as Exclude<
                  SanctionLevel,
                  'PERMANENT_SUSPENSION'
                >
                setReducedLevel(nextLevel)
                if (nextLevel !== 'FEATURE_RESTRICTION') {
                  setRestrictedFeatures([])
                }
              }}
            >
              {Object.entries(REDUCED_LEVEL_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </SelectField>

            {reducedLevel === 'FEATURE_RESTRICTION' && (
              <fieldset className="po-fieldset">
                <legend className="po-fieldset__legend">제한 기능</legend>
                {fieldErrors.restrictedFeatures !== undefined && (
                  <p className="po-fieldset__error" role="alert">
                    {fieldErrors.restrictedFeatures}
                  </p>
                )}
                {Object.entries(RESTRICTED_FEATURE_LABEL).map(
                  ([value, label]) => (
                    <label key={value} className="po-checkbox">
                      <input
                        type="checkbox"
                        checked={restrictedFeatures.includes(
                          value as RestrictedFeature,
                        )}
                        onChange={() =>
                          setRestrictedFeatures((current) =>
                            current.includes(value as RestrictedFeature)
                              ? current.filter((item) => item !== value)
                              : [...current, value as RestrictedFeature],
                          )
                        }
                      />
                      {label}
                    </label>
                  ),
                )}
              </fieldset>
            )}

            <p className="po-form__notice">
              원 제재보다 낮은 수준만 허용됩니다. 원 제재 수준은 사건 응답에 없어
              서버가 최종 검증합니다.
            </p>
          </>
        )}

        <TextField
          label="사유 코드"
          name="reasonCode"
          help="대문자·숫자·밑줄만 사용합니다."
          value={reasonCode}
          error={fieldErrors.reasonCode ?? null}
          onChange={(event) => setReasonCode(event.target.value)}
        />

        <p className="po-form__notice">
          version {supportCase.version} 기준으로 처리합니다.
        </p>

        <Button
          type="submit"
          variant="primary"
          size="lg"
          loading={submitting}
          disabled={awaitingReauthentication}
        >
          결정 기록
        </Button>
      </form>

      {awaitingReauthentication && attempt !== null && (
        <ReauthenticationDialog
          purpose={
            supportCase.caseType === 'ACCOUNT_RECOVERY'
              ? 'MEMBER_RECOVERY'
              : 'ACCOUNT_APPEAL_DECISION'
          }
          targetType={accountTargetType(supportCase.accountType)}
          targetId={supportCase.accountId}
          description={`${DECISION_LABEL[decision]} 처리를 위해 본인 확인이 필요합니다.`}
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
    return '요청을 처리하지 못했습니다.'
  }
  switch (error.code) {
    case AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT:
      return '사건 상태가 변경됐습니다. 최신 정보를 확인한 뒤 다시 시도해 주세요.'
    case AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND:
      return '사건을 찾을 수 없습니다.'
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '같은 키로 다른 내용을 보냈습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.'
    case CommonErrorCode.CONCURRENT_MODIFICATION:
      return '동시 요청이 충돌했습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요.'
    default:
      return error.message
  }
}

function formatTimestamp(isoTimestamp: string): string {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) {
    return isoTimestamp
  }
  return parsed.toLocaleString('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short',
  })
}
