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
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../platform-operator-auth'
import {
  auditQueryKeys,
  createAuditCorrection,
  fetchAuditEvent,
  type AuditAction,
  type AuditEventData,
  type AuditOutcome,
  type AuditReviewContext,
} from '../api/auditApi'
import { AuditReviewContextForm } from './AuditReviewContextForm'
import { OperatorCapabilityGate } from './OperatorAccessDenied'
import { OUTCOME_LABEL, OUTCOME_TONE, REASON_LABEL, SOURCE_LABEL } from './auditLabels'
import { useLogicalCommandAttempt } from './OperatorCommandFields'
import { ReauthenticationDialog } from './ReauthenticationDialog'
import './page.css'

/**
 * 감사 사건 상세와 보정.
 *
 * 상세 조회도 검색과 같은 사건·version·사유 헤더를 요구하고, 그 조회 자체가
 * 다시 원장에 기록된다. 그래서 목록에서 넘어와도 맥락을 다시 받는다.
 * 검색 화면의 맥락을 전역에 저장해 재사용하면, 사유가 다른 조회에 같은 사유가
 * 붙는다.
 *
 * 보정은 원 사건을 고치지 않는다. 원 사건을 보존한 채 연결된 새 사건을 추가한다.
 * 그래서 화면 문구도 "수정"이 아니라 "보정 사건 추가"다.
 */
export function AuditDetailPage() {
  return (
    <OperatorCapabilityGate permission="AUDIT_READ">
      <AuditDetailContent />
    </OperatorCapabilityGate>
  )
}

function AuditDetailContent() {
  const params = useParams<{ eventKey: string }>()
  const eventKey = params.eventKey
  const [context, setContext] = useState<AuditReviewContext | null>(null)

  if (eventKey === undefined) {
    return (
      <EmptyState
        title="잘못된 주소입니다."
        description="감사 이력에서 다시 선택해 주세요."
      />
    )
  }

  if (context === null) {
    return (
      <AuditReviewContextForm
        heading="감사 사건 상세"
        subtitle="조회할 사건과 사유를 먼저 지정합니다."
        onSubmit={setContext}
      />
    )
  }

  return (
    <AuditDetailBody
      eventKey={eventKey}
      context={context}
      onChangeContext={() => setContext(null)}
    />
  )
}

function AuditDetailBody({
  eventKey,
  context,
  onChangeContext,
}: {
  eventKey: string
  context: AuditReviewContext
  onChangeContext: () => void
}) {
  const { apiClient, capabilities } = usePlatformOperatorAuth()

  const eventQuery = useQuery({
    queryKey: auditQueryKeys.detail(context, eventKey),
    queryFn: ({ signal }) =>
      fetchAuditEvent(apiClient, context, eventKey, signal),
  })

  return (
    <section aria-labelledby="audit-detail-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="audit-detail-heading">
            감사 사건 상세
          </h1>
          <p className="po-page__subtitle">
            <Link to={ROUTES.platformOperatorAudit} className="po-table__link">
              감사 이력으로
            </Link>
            {' · '}
            사건 {context.caseId} · {REASON_LABEL[context.reasonCode]}
          </p>
        </div>
        <Button type="button" variant="ghost" onClick={onChangeContext}>
          사건·사유 변경
        </Button>
      </header>

      {eventQuery.isPending && <Loading label="감사 사건을 불러오는 중입니다." />}

      {eventQuery.isError && (
        <ErrorState
          error={eventQuery.error}
          onRetry={() => void eventQuery.refetch()}
        />
      )}

      {eventQuery.data !== undefined && (
        <>
          <AuditEventDetail event={eventQuery.data.original} />

          {/*
            보정 이력은 원 사건을 대체하지 않는다. 계약이 원 사건과 시간순 보정을
            함께 주므로 둘 다 보여 준다. 최신 보정만 그리면 무엇이 어떻게 바뀌었는지
            사라진다.
          */}
          <section aria-labelledby="corrections-heading">
            <h2 className="po-section__title" id="corrections-heading">
              보정 이력
            </h2>
            {eventQuery.data.corrections.length === 0 ? (
              <EmptyState title="보정 사건이 없습니다." />
            ) : (
              <div className="po-table-scroll">
                <table className="po-table">
                  <thead>
                    <tr>
                      <th scope="col">발생</th>
                      <th scope="col">사건 키</th>
                      <th scope="col">보정 작업</th>
                      <th scope="col">보정 결과</th>
                      <th scope="col">행위자</th>
                    </tr>
                  </thead>
                  <tbody>
                    {eventQuery.data.corrections.map((correction) => (
                      <tr key={correction.eventKey}>
                        <th scope="row">
                          {formatTimestamp(correction.occurredAt)}
                        </th>
                        <td>{correction.eventKey}</td>
                        <td>{correction.correctedAction ?? '변경 없음'}</td>
                        <td>
                          {correction.correctedOutcome == null
                            ? '변경 없음'
                            : OUTCOME_LABEL[correction.correctedOutcome]}
                        </td>
                        <td>{correction.actorOperatorId}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {decideCapability(
            capabilities,
            'OPERATOR_AUTHORITY_MANAGE',
          ) === 'allowed' && (
            <CorrectionForm
              event={eventQuery.data.original}
              context={context}
              onCorrected={() => void eventQuery.refetch()}
            />
          )}
        </>
      )}
    </section>
  )
}

function AuditEventDetail({ event }: { event: AuditEventData }) {
  return (
    <dl className="po-detail">
      <div className="po-detail__row">
        <dt>사건 키</dt>
        <dd>{event.eventKey}</dd>
      </div>
      <div className="po-detail__row">
        <dt>발생</dt>
        <dd>{formatTimestamp(event.occurredAt)}</dd>
      </div>
      <div className="po-detail__row">
        <dt>출처</dt>
        <dd>{SOURCE_LABEL[event.source] ?? event.source}</dd>
      </div>
      <div className="po-detail__row">
        <dt>작업</dt>
        <dd>{event.action}</dd>
      </div>
      <div className="po-detail__row">
        <dt>결과</dt>
        <dd>
          <Badge tone={OUTCOME_TONE[event.outcome]}>
            {OUTCOME_LABEL[event.outcome]}
          </Badge>
        </dd>
      </div>
      <div className="po-detail__row">
        <dt>행위자</dt>
        <dd>{event.actorOperatorId}</dd>
      </div>
      <div className="po-detail__row">
        <dt>대상</dt>
        <dd>
          {event.targetType} · {event.targetId}
        </dd>
      </div>
      <div className="po-detail__row">
        <dt>사유</dt>
        <dd>{event.reason}</dd>
      </div>
      {/*
        권한 변경 전후는 서버가 허용한 요약 형태로만 온다.
        비어 있는 배열을 "없음"으로 표시하고 원문 payload는 요청하지 않는다.
      */}
      <div className="po-detail__row">
        <dt>역할 변경</dt>
        <dd>
          {describeChange(event.beforeRoles, event.afterRoles)}
        </dd>
      </div>
      <div className="po-detail__row">
        <dt>권한 변경</dt>
        <dd>
          {describeChange(event.beforePermissions, event.afterPermissions)}
        </dd>
      </div>
      {event.originalEventKey != null && (
        <div className="po-detail__row">
          <dt>원 사건</dt>
          <dd>{event.originalEventKey}</dd>
        </div>
      )}
    </dl>
  )
}

/**
 * 보정 사건 추가.
 *
 * 계약이 `reason: RECORD_CORRECTION` 고정과 함께 최소 한 개의 보정 필드를
 * 요구한다(`minProperties: 2`). 그래서 아무것도 고르지 않은 제출을 막는다.
 *
 * actor, occurredAt, correlation ID와 원문 인증 사건 필드는 보정할 수 없다.
 * 화면에도 그 항목을 넣지 않는다.
 */
function CorrectionForm({
  event,
  context,
  onCorrected,
}: {
  event: AuditEventData
  context: AuditReviewContext
  onCorrected: () => void
}) {
  const { apiClient } = usePlatformOperatorAuth()
  const [correctedOutcome, setCorrectedOutcome] = useState<AuditOutcome | ''>(
    '',
  )
  const [correctedAction, setCorrectedAction] = useState<AuditAction | ''>('')
  const [correctedTargetId, setCorrectedTargetId] = useState('')
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
      eventKey: event.eventKey,
      context,
      correctedOutcome,
      correctedAction,
      correctedTargetId,
    }),
  )

  const hasCorrection =
    correctedOutcome !== '' ||
    correctedAction !== '' ||
    correctedTargetId.length > 0

  function handleRequestReauthentication(submitEvent: React.FormEvent) {
    submitEvent.preventDefault()
    setResult(null)
    if (!hasCorrection) {
      setFormError('보정할 항목을 하나 이상 지정해 주세요.')
      return
    }
    setFormError(null)
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
      const created = await createAuditCorrection(apiClient, {
        eventKey: event.eventKey,
        context,
        reauthenticationApproval: approval,
        idempotencyKey: attempt.idempotencyKey,
        body: {
          reason: 'RECORD_CORRECTION',
          ...(correctedOutcome !== '' ? { correctedOutcome } : {}),
          ...(correctedAction !== '' ? { correctedAction } : {}),
          ...(correctedTargetId.length > 0 ? { correctedTargetId } : {}),
        },
      })
      setResult(
        `보정 사건 ${created.eventKey}가 추가됐습니다. 원 사건은 그대로 보존됩니다.`,
      )
      clearAttempt()
      setCorrectedOutcome('')
      setCorrectedAction('')
      setCorrectedTargetId('')
    } catch (error) {
      setFormError(correctionErrorMessage(error))
    } finally {
      setSubmitting(false)
      onCorrected()
    }
  }

  return (
    <section aria-labelledby="correction-heading">
      <h2 className="po-section__title" id="correction-heading">
        보정 사건 추가
      </h2>

      <Alert tone="info" title="원 사건은 수정되지 않습니다.">
        보정은 원 사건을 보존한 채 연결된 새 사건을 추가합니다. 행위자·발생 시각과
        원문 인증 사건 필드는 보정할 수 없습니다.
      </Alert>

      {result !== null && <Alert tone="info" title={result} />}
      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="po-form"
        onSubmit={handleRequestReauthentication}
        aria-label="보정 사건 추가"
        inert={awaitingReauthentication}
        noValidate
      >
        <SelectField
          label="보정할 결과"
          help="변경하지 않으려면 비워 둡니다."
          value={correctedOutcome}
          onChange={(changeEvent) =>
            setCorrectedOutcome(changeEvent.target.value as AuditOutcome | '')
          }
        >
          <option value="">변경 없음</option>
          {Object.entries(OUTCOME_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>

        <TextField
          label="보정할 대상 ID"
          name="correctedTargetId"
          help="변경하지 않으려면 비워 둡니다."
          value={correctedTargetId}
          onChange={(changeEvent) =>
            setCorrectedTargetId(changeEvent.target.value)
          }
        />

        <Button
          type="submit"
          variant="primary"
          size="lg"
          loading={submitting}
          disabled={awaitingReauthentication}
        >
          보정 사건 추가
        </Button>
      </form>

      {awaitingReauthentication && attempt !== null && (
        <ReauthenticationDialog
          purpose="AUDIT_CORRECTION"
          targetType="AUDIT_EVENT"
          targetId={event.eventKey}
          description="감사 기록 보정을 위해 본인 확인이 필요합니다."
          onApproved={handleApproved}
          onCancel={() => setAwaitingReauthentication(false)}
        />
      )}
    </section>
  )
}

function describeChange(
  before: readonly string[],
  after: readonly string[],
): string {
  if (before.length === 0 && after.length === 0) {
    return '변경 없음'
  }
  const beforeText = before.length === 0 ? '없음' : before.join(', ')
  const afterText = after.length === 0 ? '없음' : after.join(', ')
  return `${beforeText} → ${afterText}`
}

function correctionErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 보정 여부를 다시 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '보정 사건을 추가하지 못했습니다.'
  }
  switch (error.code) {
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
    timeStyle: 'medium',
  })
}
