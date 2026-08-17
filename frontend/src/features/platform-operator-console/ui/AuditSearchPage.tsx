import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Badge, type BadgeTone } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { SelectField, TextField } from '../../../shared/ui/Field'
import { Alert, EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Pagination } from '../../../shared/ui/Pagination'
import { usePlatformOperatorAuth } from '../../platform-operator-auth'
import {
  auditQueryKeys,
  searchAuditEvents,
  type AuditOutcome,
  type AuditReason,
  type AuditReviewContext,
  type AuditSearchQuery,
  type EventSource,
} from '../api/auditApi'
import './page.css'

const PAGE_SIZE = 20

const OUTCOME_LABEL: Record<AuditOutcome, string> = {
  SUCCESS: '성공',
  DENIED: '거부',
  FAILED: '실패',
}

const OUTCOME_TONE: Record<AuditOutcome, BadgeTone> = {
  SUCCESS: 'positive',
  DENIED: 'attention',
  FAILED: 'negative',
}

const SOURCE_LABEL: Record<EventSource, string> = {
  AUTH: '인증',
  ADMIN: '관리 명령',
}

/** 계약이 정한 조회 사유 코드. 자유 입력이 아니다. */
const REASON_LABEL: Record<AuditReason, string> = {
  AUTHENTICATION_EVENT: '인증 사건 확인',
  ACCOUNT_PROVISIONING: '계정 발급 확인',
  RESPONSIBILITY_CHANGE: '담당 변경 확인',
  EMPLOYMENT_END: '퇴직 처리 확인',
  SECURITY_RESPONSE: '보안 대응',
  AUDIT_VERIFICATION: '감사 검증',
  RECORD_CORRECTION: '기록 보정',
}

/**
 * 감사 이력 조회.
 *
 * 시안과 두 군데가 다르다.
 *
 * 첫째, 시안의 "CSV 다운로드"와 "액션ID·참조·설명으로 검색"을 만들지 않았다.
 * 계약이 "자유형 검색·원문 payload 조회·내보내기는 없다"고 명시적으로 제외한다.
 * 필터는 계약이 주는 source·action·outcome·행위자·대상·기간뿐이다.
 *
 * 둘째, 시안에 없는 입력을 앞에 뒀다. 감사 조회는 배정받은 `AUDIT_REVIEW` 사건과
 * 사유 코드를 헤더로 함께 보내야 하고, 이 조회 자체가 원장에 기록된다.
 * 값을 화면이 지어내면 감사 기록에 거짓 사유가 남으므로 운영자가 직접 넣는다.
 */
export function AuditSearchPage() {
  const { apiClient } = usePlatformOperatorAuth()
  const [context, setContext] = useState<AuditReviewContext | null>(null)

  if (context === null) {
    return <AuditReviewContextForm onSubmit={setContext} />
  }

  return (
    <AuditSearchResults
      apiClient={apiClient}
      context={context}
      onChangeContext={() => setContext(null)}
    />
  )
}

/**
 * 조회 맥락 입력.
 *
 * 조회 전에 받는다. 결과 화면에 섞어 두면 사유를 비운 채 목록이 먼저 뜨고,
 * 그 상태에서 보낸 요청이 서버에 거부로 기록된다.
 */
function AuditReviewContextForm({
  onSubmit,
}: {
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
            감사 이력 조회
          </h1>
          <p className="po-page__subtitle">
            조회할 감사 사건과 사유를 먼저 지정합니다.
          </p>
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
          onChange={(event) =>
            setReasonCode(event.target.value as AuditReason)
          }
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

function AuditSearchResults({
  apiClient,
  context,
  onChangeContext,
}: {
  apiClient: ReturnType<typeof usePlatformOperatorAuth>['apiClient']
  context: AuditReviewContext
  onChangeContext: () => void
}) {
  const [outcome, setOutcome] = useState<AuditOutcome | ''>('')
  const [source, setSource] = useState<EventSource | ''>('')
  const [page, setPage] = useState(0)

  const query: AuditSearchQuery = {
    outcome: outcome === '' ? undefined : outcome,
    source: source === '' ? undefined : source,
    page,
    size: PAGE_SIZE,
  }

  const auditQuery = useQuery({
    queryKey: auditQueryKeys.search(context, query),
    queryFn: ({ signal }) =>
      searchAuditEvents(apiClient, context, query, signal),
  })

  return (
    <section aria-labelledby="audit-results-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="audit-results-heading">
            감사 이력 조회
          </h1>
          <p className="po-page__subtitle">
            사건 {context.caseId} · {REASON_LABEL[context.reasonCode]}
          </p>
        </div>
        <Button type="button" variant="ghost" onClick={onChangeContext}>
          사건·사유 변경
        </Button>
      </header>

      <div className="po-filters">
        <SelectField
          label="출처"
          value={source}
          onChange={(event) => {
            setSource(event.target.value as EventSource | '')
            setPage(0)
          }}
        >
          <option value="">전체</option>
          {Object.entries(SOURCE_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>

        <SelectField
          label="결과"
          value={outcome}
          onChange={(event) => {
            setOutcome(event.target.value as AuditOutcome | '')
            setPage(0)
          }}
        >
          <option value="">전체</option>
          {Object.entries(OUTCOME_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>
      </div>

      {auditQuery.isPending && (
        <Loading label="감사 이력을 불러오는 중입니다." />
      )}

      {auditQuery.isError && (
        <ErrorState
          error={auditQuery.error}
          onRetry={() => void auditQuery.refetch()}
        />
      )}

      {auditQuery.data !== undefined &&
        (auditQuery.data.content.length === 0 ? (
          <EmptyState
            title="조건에 맞는 감사 사건이 없습니다."
            description="출처나 결과 조건을 바꿔 다시 조회해 주세요."
          />
        ) : (
          <>
            <div className="po-table-scroll">
              <table className="po-table">
                <caption className="po-table__caption">
                  서버가 허용한 요약 항목만 표시됩니다. 원문 payload는 조회할 수
                  없습니다.
                </caption>
                <thead>
                  <tr>
                    <th scope="col">발생 시각</th>
                    <th scope="col">출처</th>
                    <th scope="col">작업</th>
                    <th scope="col">행위자</th>
                    <th scope="col">대상</th>
                    <th scope="col">결과</th>
                  </tr>
                </thead>
                <tbody>
                  {auditQuery.data.content.map((event) => (
                    <tr key={event.eventKey}>
                      <th scope="row">{formatTimestamp(event.occurredAt)}</th>
                      <td>{SOURCE_LABEL[event.source] ?? event.source}</td>
                      <td>{event.action}</td>
                      <td>{event.actorOperatorId}</td>
                      <td>
                        {event.targetType} · {event.targetId}
                      </td>
                      <td>
                        <Badge tone={OUTCOME_TONE[event.outcome]}>
                          {OUTCOME_LABEL[event.outcome]}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <Pagination
              number={auditQuery.data.page.number}
              totalPages={auditQuery.data.page.totalPages}
              totalElements={auditQuery.data.page.totalElements}
              hasNext={auditQuery.data.page.hasNext}
              onChange={setPage}
            />
          </>
        ))}
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
    timeStyle: 'medium',
  })
}
