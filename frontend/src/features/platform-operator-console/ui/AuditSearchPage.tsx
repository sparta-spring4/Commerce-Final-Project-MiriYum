import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { SelectField } from '../../../shared/ui/Field'
import { EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Pagination } from '../../../shared/ui/Pagination'
import { usePlatformOperatorAuth } from '../../platform-operator-auth'
import {
  auditQueryKeys,
  searchAuditEvents,
  type AuditOutcome,
  type AuditReviewContext,
  type AuditSearchQuery,
  type EventSource,
} from '../api/auditApi'
import { auditEventDetailPath } from '../model/paths'
import { AuditReviewContextForm } from './AuditReviewContextForm'
import { OUTCOME_LABEL, OUTCOME_TONE, REASON_LABEL, SOURCE_LABEL } from './auditLabels'
import './page.css'

const PAGE_SIZE = 20

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
    return (
      <AuditReviewContextForm
        heading="감사 이력 조회"
        subtitle="조회할 감사 사건과 사유를 먼저 지정합니다."
        onSubmit={setContext}
      />
    )
  }

  return (
    <AuditSearchResults
      apiClient={apiClient}
      context={context}
      onChangeContext={() => setContext(null)}
    />
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
                      <th scope="row">
                        <Link
                          to={auditEventDetailPath(event.eventKey)}
                          className="po-table__link"
                        >
                          {formatTimestamp(event.occurredAt)}
                        </Link>
                      </th>
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
