import { PLATFORM_OPERATOR_PATHS } from '../../../../app/routes/paths/platformOperatorPaths'
import { useState } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { keepsSameListConditions } from '../../../../shared/api/listQuery'
import { isApiError } from '../../../../shared/api/apiError'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Pagination } from '../../../../shared/ui/Pagination'
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../../account/platform-operator/auth'
import {
  fetchOperatorAccounts,
  operatorAccountQueryKeys,
  type OperatorAccountListQuery,
  type OperatorAccountSort,
  type OperatorAccountStatus,
  type OperatorRole,
} from '../api/operatorAccountApi'
import {
  OPERATOR_ROLE_LABEL,
  OPERATOR_STATUS_LABEL,
  OPERATOR_STATUS_TONE,
} from '../model/operatorLabels'
import { operatorDetailPath } from '../model/paths'
import { OperatorAccessDenied } from './OperatorAccessDenied'
import './page.css'

const PAGE_SIZE = 20

/** 계약이 허용하는 정렬 조합만 제시한다. */
const SORT_LABEL: Record<OperatorAccountSort, string> = {
  'operatorId,asc': '운영자 ID 오름차순',
  'operatorId,desc': '운영자 ID 내림차순',
  'displayName,asc': '표시명 오름차순',
  'displayName,desc': '표시명 내림차순',
  'status,asc': '상태 오름차순',
  'status,desc': '상태 내림차순',
  'lastLoginAt,asc': '최근 로그인 오래된 순',
  'lastLoginAt,desc': '최근 로그인 최신 순',
}

/**
 * 운영자 계정 목록.
 *
 * 조회에 `OPERATOR_AUTHORITY_MANAGE`가 필요하다. 권한이 없으면 서버가 403으로
 * 거절하며, 화면은 그 상태를 일반 오류와 구분해 보여 준다.
 *
 * 이메일은 서버가 마스킹한 값을 그대로 쓴다. 원문을 요청하거나 복원하지 않는다.
 * 계약에 없는 로그인 이력·통계·임시 비밀번호는 만들지 않는다.
 */
export function OperatorListPage() {
  const { apiClient, capabilities } = usePlatformOperatorAuth()
  const canCreate =
    decideCapability(capabilities, 'OPERATOR_CREATE') === 'allowed'
  const [status, setStatus] = useState<OperatorAccountStatus | ''>('')
  const [role, setRole] = useState<OperatorRole | ''>('')
  const [searchInput, setSearchInput] = useState('')
  const [appliedQuery, setAppliedQuery] = useState('')
  const [sort, setSort] = useState<OperatorAccountSort>('displayName,asc')
  const [page, setPage] = useState(0)

  const query: OperatorAccountListQuery = {
    status: status === '' ? undefined : status,
    role: role === '' ? undefined : role,
    query: appliedQuery.length > 0 ? appliedQuery : undefined,
    sort,
    page,
    size: PAGE_SIZE,
  }

  const accountsQuery = useQuery({
    queryKey: operatorAccountQueryKeys.list(query),
    queryFn: ({ signal }) => fetchOperatorAccounts(apiClient, query, signal),
    // 서버가 권한을 확인해 준 뒤에만 조회한다.
    enabled:
      decideCapability(capabilities, 'OPERATOR_AUTHORITY_MANAGE') === 'allowed',
    placeholderData: (previous, previousQuery) =>
      keepsSameListConditions(previousQuery?.queryKey, query)
        ? previous
        : undefined,
  })

  /** 조건이 바뀌면 첫 페이지로 돌아간다. 빈 뒷페이지에 남지 않게 한다. */
  function changeCondition(next: () => void) {
    next()
    setPage(0)
  }

  function handleSearch(event: React.FormEvent) {
    event.preventDefault()
    // 입력할 때마다 조회하지 않는다. 검색은 명시적 제출로만 나간다.
    changeCondition(() => setAppliedQuery(searchInput.trim()))
  }

  const denied =
    accountsQuery.isError && isApiError(accountsQuery.error)
      ? accountsQuery.error.status === 403
      : false

  return (
    <section aria-labelledby="operator-list-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="operator-list-heading">
            운영자 관리
          </h1>
          <p className="po-page__subtitle">
            플랫폼 운영자 계정과 권한을 조회하고 관리합니다.
          </p>
        </div>
        {/*
          전체 페이지 이동을 쓰지 않는다. Access Token은 provider의 ref에만
          있으므로 리로드하면 세션 복구를 다시 거쳐야 한다.
        */}
        {canCreate && (
          <Link className="po-button-link" to={PLATFORM_OPERATOR_PATHS.operatorCreate}>
            운영자 등록
          </Link>
        )}
      </header>

      <div className="po-filters">
        <SelectField
          label="상태"
          value={status}
          onChange={(event) =>
            changeCondition(() =>
              setStatus(event.target.value as OperatorAccountStatus | ''),
            )
          }
        >
          <option value="">전체</option>
          {Object.entries(OPERATOR_STATUS_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>

        <SelectField
          label="역할"
          value={role}
          onChange={(event) =>
            changeCondition(() => setRole(event.target.value as OperatorRole | ''))
          }
        >
          <option value="">전체</option>
          {Object.entries(OPERATOR_ROLE_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>

        <SelectField
          label="정렬"
          value={sort}
          onChange={(event) =>
            changeCondition(() =>
              setSort(event.target.value as OperatorAccountSort),
            )
          }
        >
          {Object.entries(SORT_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SelectField>

        <form className="po-filters__search" onSubmit={handleSearch}>
          <TextField
            label="검색"
            name="query"
            help="운영자 ID·이메일·표시명으로 찾습니다."
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
          />
          <Button type="submit" variant="ghost">
            검색
          </Button>
        </form>
      </div>

      {accountsQuery.isPending && (
        <Loading label="운영자 목록을 불러오는 중입니다." />
      )}

      {denied && <OperatorAccessDenied />}

      {accountsQuery.isError && !denied && (
        <ErrorState
          error={accountsQuery.error}
          onRetry={() => void accountsQuery.refetch()}
        />
      )}

      {accountsQuery.data !== undefined &&
        (accountsQuery.data.content.length === 0 ? (
          <EmptyState
            title="조건에 맞는 운영자가 없습니다."
            description="상태·역할 조건을 바꾸거나 검색어를 지워 다시 조회해 주세요."
          />
        ) : (
          <>
            <div className="po-table-scroll">
              <table className="po-table">
                <caption className="po-table__caption">
                  이메일은 서버가 마스킹한 값입니다.
                </caption>
                <thead>
                  <tr>
                    <th scope="col">표시명</th>
                    <th scope="col">운영자 ID</th>
                    <th scope="col">이메일</th>
                    <th scope="col">상태</th>
                    <th scope="col">역할</th>
                    <th scope="col">권한 version</th>
                    <th scope="col">최근 로그인</th>
                  </tr>
                </thead>
                <tbody>
                  {accountsQuery.data.content.map((account) => (
                    <tr key={account.operatorId}>
                      <th scope="row">
                        <Link
                          to={operatorDetailPath(account.operatorId)}
                          className="po-table__link"
                        >
                          {account.displayName}
                        </Link>
                      </th>
                      <td>{account.operatorId}</td>
                      <td>{account.email}</td>
                      <td>
                        <Badge tone={OPERATOR_STATUS_TONE[account.status]}>
                          {OPERATOR_STATUS_LABEL[account.status]}
                        </Badge>
                        {/*
                          임시 비밀번호 상태는 계정이 아직 정상 사용 전이라는 뜻이다.
                          상태 뱃지와 별도로 표시해 중지와 혼동되지 않게 한다.
                        */}
                        {account.passwordChangeRequired && (
                          <span className="po-table__note">
                            비밀번호 변경 전
                          </span>
                        )}
                      </td>
                      <td>
                        {account.roles.length === 0
                          ? '없음'
                          : account.roles
                              .map((value) => OPERATOR_ROLE_LABEL[value])
                              .join(', ')}
                      </td>
                      <td>{account.authorityVersion}</td>
                      <td>
                        {account.lastLoginAt == null
                          ? '기록 없음'
                          : formatTimestamp(account.lastLoginAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <Pagination
              number={accountsQuery.data.page.number}
              totalPages={accountsQuery.data.page.totalPages}
              totalElements={accountsQuery.data.page.totalElements}
              hasNext={accountsQuery.data.page.hasNext}
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
    timeStyle: 'short',
  })
}
