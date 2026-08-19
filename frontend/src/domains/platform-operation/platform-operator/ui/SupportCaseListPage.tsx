import { useState } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { keepsSameListConditions } from '../../../../shared/api/listQuery'
import { Badge, type BadgeTone } from '../../../../shared/ui/Badge'
import { SelectField } from '../../../../shared/ui/Field'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Pagination } from '../../../../shared/ui/Pagination'
import { usePlatformOperatorAuth } from '../../../account/platform-operator/auth'
import {
  fetchSupportCases,
  supportCaseQueryKeys,
  type CaseStatus,
  type CaseType,
  type SupportCaseListQuery,
} from '../api/memberSupportApi'
import { supportCaseDetailPath } from '../model/paths'
import { OperatorCapabilityGate } from './OperatorAccessDenied'
import './page.css'

const PAGE_SIZE = 20

export const CASE_TYPE_LABEL: Record<CaseType, string> = {
  ACCOUNT_RECOVERY: '계정 복구',
  ACCOUNT_APPEAL: '제재 이의',
}

export const CASE_STATUS_LABEL: Record<CaseStatus, string> = {
  SUBMITTED: '접수',
  ASSIGNED: '배정됨',
  APPROVED: '승인',
  REJECTED: '반려',
  UPHELD: '제재 유지',
  REDUCED: '제재 경감',
  CANCELLED: '취소',
}

export const CASE_STATUS_TONE: Record<CaseStatus, BadgeTone> = {
  SUBMITTED: 'attention',
  ASSIGNED: 'neutral',
  APPROVED: 'positive',
  REJECTED: 'negative',
  UPHELD: 'negative',
  REDUCED: 'attention',
  CANCELLED: 'neutral',
}

/**
 * 회원지원 사건 목록.
 *
 * 계정 복구와 제재 이의가 한 원장에 있다. 계약이 주는 필터는 유형·상태·page뿐이다.
 * 담당자 필터는 계약에 없어 만들지 않았다.
 */
export function SupportCaseListPage() {
  return (
    <OperatorCapabilityGate permission="MEMBER_READ_MINIMAL">
      <SupportCaseListContent />
    </OperatorCapabilityGate>
  )
}

function SupportCaseListContent() {
  const { apiClient } = usePlatformOperatorAuth()
  const [caseType, setCaseType] = useState<CaseType | ''>('')
  const [status, setStatus] = useState<CaseStatus | ''>('')
  const [page, setPage] = useState(0)

  const query: SupportCaseListQuery = {
    caseType: caseType === '' ? undefined : caseType,
    status: status === '' ? undefined : status,
    page,
    size: PAGE_SIZE,
  }

  const casesQuery = useQuery({
    queryKey: supportCaseQueryKeys.list(query),
    queryFn: ({ signal }) => fetchSupportCases(apiClient, query, signal),
    placeholderData: (previous, previousQuery) =>
      keepsSameListConditions(previousQuery?.queryKey, query)
        ? previous
        : undefined,
  })

  return (
    <section aria-labelledby="support-case-list-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="support-case-list-heading">
            회원지원 사건
          </h1>
          <p className="po-page__subtitle">
            계정 복구와 제재 이의 사건을 배정하고 결정합니다.
          </p>
        </div>
      </header>

      <div className="po-filters">
        <SelectField
          label="사건 유형"
          value={caseType}
          onChange={(event) => {
            setCaseType(event.target.value as CaseType | '')
            setPage(0)
          }}
        >
          <option value="">전체</option>
          {Object.entries(CASE_TYPE_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>

        <SelectField
          label="상태"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as CaseStatus | '')
            setPage(0)
          }}
        >
          <option value="">전체</option>
          {Object.entries(CASE_STATUS_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>
      </div>

      {casesQuery.isPending && (
        <Loading label="사건 목록을 불러오는 중입니다." />
      )}

      {casesQuery.isError && (
        <ErrorState
          error={casesQuery.error}
          onRetry={() => void casesQuery.refetch()}
        />
      )}

      {casesQuery.data !== undefined &&
        (casesQuery.data.content.length === 0 ? (
          <EmptyState
            title="조건에 맞는 사건이 없습니다."
            description="유형이나 상태를 바꿔 다시 조회해 주세요."
          />
        ) : (
          <>
            <div className="po-table-scroll">
              <table className="po-table">
                <thead>
                  <tr>
                    <th scope="col">사건</th>
                    <th scope="col">유형</th>
                    <th scope="col">대상</th>
                    <th scope="col">상태</th>
                    <th scope="col">담당</th>
                    <th scope="col">접수</th>
                  </tr>
                </thead>
                <tbody>
                  {casesQuery.data.content.map((supportCase) => (
                    <tr key={supportCase.caseId}>
                      <th scope="row">
                        <Link
                          to={supportCaseDetailPath(supportCase.caseId)}
                          className="po-table__link"
                        >
                          {supportCase.caseId}
                        </Link>
                      </th>
                      <td>{CASE_TYPE_LABEL[supportCase.caseType]}</td>
                      <td>
                        {supportCase.accountType} · {supportCase.accountId}
                      </td>
                      <td>
                        <Badge tone={CASE_STATUS_TONE[supportCase.status]}>
                          {CASE_STATUS_LABEL[supportCase.status]}
                        </Badge>
                      </td>
                      {/* 미배정을 빈 칸으로 두지 않는다. 담당이 없다는 것도 정보다. */}
                      <td>{supportCase.assignedOperatorId ?? '미배정'}</td>
                      <td>{formatDate(supportCase.submittedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <Pagination
              number={casesQuery.data.page.number}
              totalPages={casesQuery.data.page.totalPages}
              totalElements={casesQuery.data.page.totalElements}
              hasNext={casesQuery.data.page.hasNext}
              onChange={setPage}
            />
          </>
        ))}
    </section>
  )
}

function formatDate(isoTimestamp: string): string {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) {
    return isoTimestamp
  }
  return parsed.toLocaleDateString('ko-KR', { dateStyle: 'medium' })
}
