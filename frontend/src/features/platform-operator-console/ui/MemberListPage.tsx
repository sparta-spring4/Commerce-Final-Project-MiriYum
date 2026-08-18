import { useState } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { keepsSameListConditions } from '../../../shared/api/consumerSession'
import { Badge, type BadgeTone } from '../../../shared/ui/Badge'
import { SelectField } from '../../../shared/ui/Field'
import { EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Pagination } from '../../../shared/ui/Pagination'
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../platform-operator-auth'
import {
  fetchMembers,
  memberQueryKeys,
  type AccountType,
  type Member,
  type MemberListQuery,
  type MemberStatus,
} from '../api/memberSupportApi'
import { memberDetailPath } from '../model/paths'
import './page.css'

const PAGE_SIZE = 20

const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  CONSUMER: '일반 사용자',
  STORE_OPERATOR: '매장 운영자',
}

const STATUS_LABEL: Record<MemberStatus, string> = {
  ACTIVE: '활성',
  PASSWORD_RESET_REQUIRED: '비밀번호 재설정 필요',
  FEATURE_RESTRICTED: '기능 제한',
  TEMPORARILY_SUSPENDED: '기간 정지',
  PERMANENTLY_SUSPENDED: '영구 정지',
}

/** 정지·제한을 활성과 같은 색으로 두지 않는다. 색만으로 뜻을 전하지도 않는다. */
const STATUS_TONE: Record<MemberStatus, BadgeTone> = {
  ACTIVE: 'positive',
  PASSWORD_RESET_REQUIRED: 'attention',
  FEATURE_RESTRICTED: 'attention',
  TEMPORARILY_SUSPENDED: 'negative',
  PERMANENTLY_SUSPENDED: 'negative',
}

/**
 * 회원 목록.
 *
 * 계약이 주는 필터는 계정 유형·상태·가입 기간·page뿐이다. 시안의 자유형
 * "ID 또는 이름 검색"은 계약에 없어 만들지 않았고, 하단 통계 카드(총 활성 사용자,
 * 신규 가입, 유형 분포)도 집계 endpoint가 없어 만들지 않았다. "최근 활동" 컬럼도
 * 응답에 해당 필드가 없다.
 *
 * 이 조회는 최소 식별정보만 다룬다. 이름·이메일·전화번호가 응답에 없는 것은
 * 누락이 아니라 계약의 설계다.
 */
export function MemberListPage() {
  const { apiClient, capabilities } = usePlatformOperatorAuth()
  const [accountType, setAccountType] = useState<AccountType | ''>('')
  const [status, setStatus] = useState<MemberStatus | ''>('')
  const [page, setPage] = useState(0)

  const query: MemberListQuery = {
    accountType: accountType === '' ? undefined : accountType,
    status: status === '' ? undefined : status,
    page,
    size: PAGE_SIZE,
  }

  const membersQuery = useQuery({
    queryKey: memberQueryKeys.list(query),
    queryFn: ({ signal }) => fetchMembers(apiClient, query, signal),
    /*
     * 서버가 권한 없음을 확인해 준 경우에는 조회를 보내지 않는다.
     * 거부될 걸 알면서 보낸 요청도 감사 원장에 기록된다.
     *
     * 권한 snapshot을 아직 읽지 못한 구간에도 조회를 보내지 않는다.
     */
    enabled: decideCapability(capabilities, 'MEMBER_READ_MINIMAL') === 'allowed',
    // 페이지만 넘길 때는 이전 결과를 유지해 목록이 깜빡이지 않게 한다.
    // 조건이 바뀌면 유지하지 않는다. 필터는 새 조건인데 목록은 옛 조건인
    // 구간이 생기면, 그 사이 누른 항목이 옛 조건의 대상으로 이동한다.
    placeholderData: (previous, previousQuery) =>
      keepsSameListConditions(previousQuery?.queryKey, query)
        ? previous
        : undefined,
  })

  function changeFilter(next: () => void) {
    next()
    // 조건이 바뀌면 첫 페이지로 돌아간다. 3페이지에 있다가 조건을 좁히면
    // 결과가 1페이지뿐인데 빈 3페이지를 보게 된다.
    setPage(0)
  }

  return (
    <section aria-labelledby="member-list-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="member-list-heading">
            회원 관리
          </h1>
          <p className="po-page__subtitle">
            권한 범위의 계정을 조회하고 제재·복구 업무를 수행합니다.
          </p>
        </div>
      </header>

      <div className="po-filters">
        <SelectField
          label="계정 유형"
          value={accountType}
          onChange={(event) =>
            changeFilter(() =>
              setAccountType(event.target.value as AccountType | ''),
            )
          }
        >
          <option value="">전체</option>
          <option value="CONSUMER">{ACCOUNT_TYPE_LABEL.CONSUMER}</option>
          <option value="STORE_OPERATOR">
            {ACCOUNT_TYPE_LABEL.STORE_OPERATOR}
          </option>
        </SelectField>

        <SelectField
          label="상태"
          value={status}
          onChange={(event) =>
            changeFilter(() => setStatus(event.target.value as MemberStatus | ''))
          }
        >
          <option value="">전체</option>
          {Object.entries(STATUS_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>
      </div>

      <MemberListBody
        isPending={membersQuery.isPending}
        isError={membersQuery.isError}
        error={membersQuery.error}
        members={membersQuery.data?.content ?? []}
        onRetry={() => void membersQuery.refetch()}
      />

      {membersQuery.data !== undefined &&
        membersQuery.data.content.length > 0 && (
          <Pagination
            number={membersQuery.data.page.number}
            totalPages={membersQuery.data.page.totalPages}
            totalElements={membersQuery.data.page.totalElements}
            hasNext={membersQuery.data.page.hasNext}
            onChange={setPage}
          />
        )}
    </section>
  )
}

function MemberListBody({
  isPending,
  isError,
  error,
  members,
  onRetry,
}: {
  isPending: boolean
  isError: boolean
  error: unknown
  members: readonly Member[]
  onRetry: () => void
}) {
  if (isPending) {
    return <Loading label="회원 목록을 불러오는 중입니다." />
  }
  if (isError) {
    return <ErrorState error={error} onRetry={onRetry} />
  }
  if (members.length === 0) {
    return (
      <EmptyState
        title="조건에 맞는 회원이 없습니다."
        description="계정 유형이나 상태를 바꿔 다시 조회해 주세요."
      />
    )
  }

  return (
    <div className="po-table-scroll">
      <table className="po-table">
        <caption className="po-table__caption">
          권한 범위 안의 회원 목록입니다. 식별정보는 최소 항목만 표시됩니다.
        </caption>
        <thead>
          <tr>
            <th scope="col">식별자</th>
            <th scope="col">계정 유형</th>
            <th scope="col">상태</th>
            <th scope="col">가입일</th>
            <th scope="col">활성 제재</th>
          </tr>
        </thead>
        <tbody>
          {members.map((member) => (
            <tr key={`${member.accountType}:${member.accountId}`}>
              <th scope="row">
                <Link
                  to={memberDetailPath(member.accountType, member.accountId)}
                  className="po-table__link"
                >
                  {member.accountId}
                </Link>
              </th>
              <td>{ACCOUNT_TYPE_LABEL[member.accountType]}</td>
              <td>
                <Badge tone={STATUS_TONE[member.status]}>
                  {STATUS_LABEL[member.status]}
                </Badge>
              </td>
              <td>{formatDate(member.joinedAt)}</td>
              <td>
                {member.activeSanctions.length === 0
                  ? '없음'
                  : `${member.activeSanctions.length}건`}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function formatDate(isoTimestamp: string): string {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) {
    return isoTimestamp
  }
  return parsed.toLocaleDateString('ko-KR', { dateStyle: 'medium' })
}
