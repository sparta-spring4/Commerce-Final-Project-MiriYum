import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { isApiError } from '../../../shared/api/apiError'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { SelectField, TextField } from '../../../shared/ui/Field'
import { Alert, EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../platform-operator-auth'
import type { AuditReason } from '../api/operatorAccountApi'
import { OPERATOR_REASON_LABEL } from '../model/operatorLabels'
import {
  assignStoreSanctionCase,
  fetchStoreSanctionCase,
  storeQueryKeys,
  type StoreCaseContext,
  type StoreSanctionCaseDetail,
} from '../api/storeAdminApi'
import {
  STORE_CASE_STATUS_LABEL,
  STORE_CASE_STATUS_TONE,
} from '../model/storeLabels'
import { storeDetailPath } from '../model/paths'
import { OperatorAccessDenied } from './OperatorAccessDenied'
import { StoreSanctionForm, sanctionErrorMessage } from './StoreSanctionForm'
import { StoreSanctionList } from './StoreSanctionList'
import './page.css'

/** 매장 제재 사건 조회에 쓸 수 있는 사유. */
const CASE_REASONS: readonly AuditReason[] = [
  'SECURITY_RESPONSE',
  'AUDIT_VERIFICATION',
  'STORE_ENFORCEMENT',
]

/**
 * 매장 제재 사건 상세.
 *
 * 조회가 사건 ID·version·사유를 헤더로 요구한다. 사건 ID는 주소에 있지만
 * version과 사유는 없으므로 먼저 입력받는다. 값을 화면이 지어내면 감사 원장에
 * 거짓 맥락이 남고, version이 틀리면 서버가 거절한다.
 */
export function StoreSanctionCasePage() {
  const { capabilities } = usePlatformOperatorAuth()
  const params = useParams<{ storeId: string; caseId: string }>()
  const [context, setContext] = useState<StoreCaseContext | null>(null)

  const storeId = Number(params.storeId)
  const caseId = params.caseId
  const validParams =
    Number.isInteger(storeId) && storeId > 0 && caseId !== undefined

  if (decideCapability(capabilities, 'STORE_READ_MINIMAL') === 'denied') {
    return <OperatorAccessDenied />
  }

  if (!validParams) {
    return (
      <EmptyState
        title="잘못된 주소입니다."
        description="매장 상세에서 사건을 다시 선택해 주세요."
      />
    )
  }

  if (context === null) {
    return (
      <CaseContextForm
        storeId={storeId}
        caseId={caseId}
        onSubmit={setContext}
      />
    )
  }

  return (
    <StoreSanctionCaseBody
      storeId={storeId}
      context={context}
      onChangeContext={() => setContext(null)}
      onContextVersionChange={(caseVersion) =>
        setContext((current) =>
          current === null ? null : { ...current, caseVersion },
        )
      }
    />
  )
}

/**
 * 사건 맥락 입력.
 *
 * case version은 매장 상세가 주지 않는다. 계약이 사건 목록 조회를 제공하지
 * 않으므로 운영자가 배정 통보나 감사 기록에서 확인한 값을 넣는다.
 */
function CaseContextForm({
  storeId,
  caseId,
  onSubmit,
}: {
  storeId: number
  caseId: string
  onSubmit: (context: StoreCaseContext) => void
}) {
  const [caseVersion, setCaseVersion] = useState('')
  const [reasonCode, setReasonCode] = useState<AuditReason>('STORE_ENFORCEMENT')
  const [errors, setErrors] = useState<Record<string, string>>({})

  return (
    <section aria-labelledby="case-context-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="case-context-heading">
            제재 사건 상세
          </h1>
          <p className="po-page__subtitle">
            <Link to={storeDetailPath(storeId)} className="po-table__link">
              매장 상세로
            </Link>
            {' · '}
            사건 {caseId}
          </p>
        </div>
      </header>

      <Alert tone="info" title="이 조회는 감사 기록에 남습니다.">
        사건 version과 조회 사유를 입력해 주세요. 값을 넣기 전에는 조회가
        실행되지 않습니다.
      </Alert>

      <form
        className="po-form"
        aria-label="사건 조회 맥락"
        onSubmit={(event) => {
          event.preventDefault()
          const parsed = Number(caseVersion)
          if (
            caseVersion.trim().length === 0 ||
            !Number.isInteger(parsed) ||
            parsed < 1
          ) {
            setErrors({ caseVersion: '사건 version을 숫자로 입력해 주세요.' })
            return
          }
          setErrors({})
          onSubmit({ caseId, caseVersion: parsed, reasonCode })
        }}
        noValidate
      >
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
          onChange={(event) =>
            setReasonCode(event.target.value as AuditReason)
          }
        >
          {CASE_REASONS.map((value) => (
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

function StoreSanctionCaseBody({
  storeId,
  context,
  onChangeContext,
  onContextVersionChange,
}: {
  storeId: number
  context: StoreCaseContext
  onChangeContext: () => void
  onContextVersionChange: (caseVersion: number) => void
}) {
  const { apiClient, capabilities } = usePlatformOperatorAuth()
  const canSanction =
    decideCapability(capabilities, 'STORE_SANCTION') === 'allowed'

  const caseQuery = useQuery({
    queryKey: storeQueryKeys.sanctionCase(context, storeId),
    queryFn: ({ signal }) =>
      fetchStoreSanctionCase(apiClient, storeId, context, signal),
  })

  const denied =
    caseQuery.isError && isApiError(caseQuery.error)
      ? caseQuery.error.status === 403
      : false

  return (
    <section aria-labelledby="case-detail-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="case-detail-heading">
            제재 사건 상세
          </h1>
          <p className="po-page__subtitle">
            <Link to={storeDetailPath(storeId)} className="po-table__link">
              매장 상세로
            </Link>
            {' · '}
            사유 {OPERATOR_REASON_LABEL[context.reasonCode as AuditReason]}
          </p>
        </div>
        <Button type="button" variant="ghost" onClick={onChangeContext}>
          version·사유 변경
        </Button>
      </header>

      {caseQuery.isPending && <Loading label="사건을 불러오는 중입니다." />}

      {denied && <OperatorAccessDenied />}

      {caseQuery.isError && !denied && (
        <ErrorState
          error={caseQuery.error}
          onRetry={() => void caseQuery.refetch()}
        />
      )}

      {caseQuery.data !== undefined && (
        <>
          <CaseSummary detail={caseQuery.data} storeId={storeId} />

          <CaseAssignment
            storeId={storeId}
            context={context}
            detail={caseQuery.data}
            onAssigned={(caseVersion) => {
              if (caseVersion === undefined) {
                void caseQuery.refetch()
              } else {
                onContextVersionChange(caseVersion)
              }
            }}
          />

          <StoreSanctionList
            storeId={storeId}
            context={context}
            sanctions={caseQuery.data.sanctions}
            canSanction={canSanction}
            onChanged={() => void caseQuery.refetch()}
          />

          {canSanction && (
            <StoreSanctionForm
              storeId={storeId}
              context={context}
              onApplied={() => void caseQuery.refetch()}
            />
          )}
        </>
      )}
    </section>
  )
}

function CaseSummary({
  detail,
  storeId,
}: {
  detail: StoreSanctionCaseDetail
  storeId: number
}) {
  return (
    <dl className="po-detail">
      <div className="po-detail__row">
        <dt>사건 ID</dt>
        <dd>{detail.caseId}</dd>
      </div>
      <div className="po-detail__row">
        <dt>매장 ID</dt>
        <dd>{storeId}</dd>
      </div>
      <div className="po-detail__row">
        <dt>위반 유형</dt>
        <dd>{detail.violationType}</dd>
      </div>
      <div className="po-detail__row">
        <dt>정책 버전</dt>
        <dd>{detail.policyVersion}</dd>
      </div>
      <div className="po-detail__row">
        <dt>상태</dt>
        <dd>
          <Badge tone={STORE_CASE_STATUS_TONE[detail.status]}>
            {STORE_CASE_STATUS_LABEL[detail.status]}
          </Badge>
        </dd>
      </div>
      <div className="po-detail__row">
        <dt>case version</dt>
        <dd>{detail.caseVersion}</dd>
      </div>
      <div className="po-detail__row">
        <dt>생성자</dt>
        <dd>{detail.createdBy}</dd>
      </div>
      <div className="po-detail__row">
        <dt>담당자</dt>
        {/* 미배정을 빈 칸으로 두지 않는다. 담당이 없다는 것도 정보다. */}
        <dd>{detail.assignedOperatorId ?? '미배정'}</dd>
      </div>
      <div className="po-detail__row">
        <dt>생성 시각</dt>
        <dd>{formatTimestamp(detail.createdAt)}</dd>
      </div>
      <div className="po-detail__row">
        <dt>증거 참조</dt>
        <dd>
          <ul className="po-inline-list">
            {detail.evidenceReferences.map((reference) => (
              <li key={reference}>{reference}</li>
            ))}
          </ul>
        </dd>
      </div>
    </dl>
  )
}

/**
 * 자기 배정.
 *
 * 사건 생성과 분리한다. 생성자에게 자동 배정되지 않으므로, 생성 직후에도
 * 이 단계를 거쳐야 담당자가 된다.
 */
function CaseAssignment({
  storeId,
  context,
  detail,
  onAssigned,
}: {
  storeId: number
  context: StoreCaseContext
  detail: StoreSanctionCaseDetail
  onAssigned: (caseVersion?: number) => void
}) {
  const { apiClient } = usePlatformOperatorAuth()
  const [assigning, setAssigning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [idempotencyKey, setIdempotencyKey] = useState(() =>
    createIdempotencyKey(),
  )

  if (detail.assignedOperatorId != null) {
    return (
      <section aria-labelledby="assignment-heading">
        <h2 className="po-section__title" id="assignment-heading">
          담당 배정
        </h2>
        <Alert tone="info" title="이미 배정된 사건입니다.">
          담당 운영자 {detail.assignedOperatorId}가 처리 중입니다.
        </Alert>
      </section>
    )
  }

  async function handleAssign() {
    setAssigning(true)
    setError(null)
    let assignedCaseVersion: number | undefined
    try {
      const assigned = await assignStoreSanctionCase(apiClient, {
        storeId,
        caseId: context.caseId,
        reasonCode: context.reasonCode,
        idempotencyKey,
        expectedCaseVersion: detail.caseVersion,
      })
      assignedCaseVersion = assigned.caseVersion
      setIdempotencyKey(createIdempotencyKey())
    } catch (caught) {
      setError(sanctionErrorMessage(caught))
    } finally {
      setAssigning(false)
      // 성공이면 응답의 새 version으로, 실패면 기존 맥락으로 상태를 다시 읽는다.
      onAssigned(assignedCaseVersion)
    }
  }

  return (
    <section aria-labelledby="assignment-heading">
      <h2 className="po-section__title" id="assignment-heading">
        담당 배정
      </h2>
      {error !== null && <Alert tone="error" title={error} />}
      <p className="po-form__notice">
        case version {detail.caseVersion} 기준으로 자기 자신에게 배정합니다.
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
  )
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
