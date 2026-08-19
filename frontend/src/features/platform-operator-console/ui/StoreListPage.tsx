import { useState } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { isApiError } from '../../../shared/api/apiError'
import { keepsSameListConditions } from '../../../shared/api/consumerSession'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { SelectField, TextField } from '../../../shared/ui/Field'
import { EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Pagination } from '../../../shared/ui/Pagination'
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../platform-operator-auth'
import type { AuditReason } from '../api/operatorAccountApi'
import {
  fetchStores,
  storeQueryKeys,
  type StoreListQuery,
  type StoreOperationStatus,
} from '../api/storeAdminApi'
import {
  STORE_OPERATION_STATUS_LABEL,
  STORE_OPERATION_STATUS_TONE,
  STORE_SANCTION_TYPE_LABEL,
} from '../model/storeLabels'
import { storeDetailPath } from '../model/paths'
import { AdminReasonGate } from './AdminReasonGate'
import { OperatorAccessDenied } from './OperatorAccessDenied'
import './page.css'

const PAGE_SIZE = 20

/**
 * 매장 목록.
 *
 * 조회 사유를 고르기 전에는 아무 요청도 나가지 않는다(`AdminReasonGate`).
 *
 * 계약이 주는 것은 매장 ID·이름·운영자 계정 ID·운영 상태·기능 활성 여부·
 * enforcement version·활성 제재 유형뿐이다. 시안이 그린 점주명·업종·지역·
 * 최근 활동·데이터 내보내기·신규 등록은 계약에 없어 만들지 않았다.
 */
export function StoreListPage() {
  const { capabilities } = usePlatformOperatorAuth()
  const [reasonCode, setReasonCode] = useState<AuditReason | null>(null)

  if (decideCapability(capabilities, 'STORE_READ_MINIMAL') === 'denied') {
    return <OperatorAccessDenied />
  }

  if (reasonCode === null) {
    return (
      <AdminReasonGate
        heading="매장 관리"
        subtitle="조회 사유를 먼저 지정합니다."
        onSubmit={setReasonCode}
      />
    )
  }

  return (
    <StoreListResults
      reasonCode={reasonCode}
      onChangeReason={() => setReasonCode(null)}
    />
  )
}

function StoreListResults({
  reasonCode,
  onChangeReason,
}: {
  reasonCode: AuditReason
  onChangeReason: () => void
}) {
  const { apiClient, capabilities } = usePlatformOperatorAuth()
  const [keywordInput, setKeywordInput] = useState('')
  const [appliedKeyword, setAppliedKeyword] = useState('')
  const [operationStatus, setOperationStatus] = useState<
    StoreOperationStatus | ''
  >('')
  const [page, setPage] = useState(0)

  const query: StoreListQuery = {
    keyword: appliedKeyword.length > 0 ? appliedKeyword : undefined,
    operationStatus: operationStatus === '' ? undefined : operationStatus,
    page,
    size: PAGE_SIZE,
  }

  const storesQuery = useQuery({
    queryKey: storeQueryKeys.list(reasonCode, query),
    queryFn: ({ signal }) =>
      fetchStores(apiClient, reasonCode, query, signal),
    enabled: decideCapability(capabilities, 'STORE_READ_MINIMAL') === 'allowed',
    placeholderData: (previous, previousQuery) =>
      keepsSameListConditions(previousQuery?.queryKey, query)
        ? previous
        : undefined,
  })

  const denied =
    storesQuery.isError && isApiError(storesQuery.error)
      ? storesQuery.error.status === 403
      : false

  return (
    <section aria-labelledby="store-list-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="store-list-heading">
            매장 관리
          </h1>
          <p className="po-page__subtitle">
            플랫폼 차원의 매장 상태를 조회하고 제재 사건을 처리합니다.
          </p>
        </div>
        <Button type="button" variant="ghost" onClick={onChangeReason}>
          조회 사유 변경
        </Button>
      </header>

      <div className="po-filters">
        <SelectField
          label="운영 상태"
          value={operationStatus}
          onChange={(event) => {
            setOperationStatus(event.target.value as StoreOperationStatus | '')
            setPage(0)
          }}
        >
          <option value="">전체</option>
          {Object.entries(STORE_OPERATION_STATUS_LABEL).map(
            ([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ),
          )}
        </SelectField>

        <form
          className="po-filters__search"
          onSubmit={(event) => {
            event.preventDefault()
            // 타이핑마다 조회하지 않는다. 각 조회가 감사 원장에 기록된다.
            setAppliedKeyword(keywordInput.trim())
            setPage(0)
          }}
        >
          <TextField
            label="검색"
            name="keyword"
            help="매장명 또는 매장 ID로 찾습니다."
            value={keywordInput}
            onChange={(event) => setKeywordInput(event.target.value)}
          />
          <Button type="submit" variant="ghost">
            검색
          </Button>
        </form>
      </div>

      {storesQuery.isPending && (
        <Loading label="매장 목록을 불러오는 중입니다." />
      )}

      {denied && <OperatorAccessDenied />}

      {storesQuery.isError && !denied && (
        <ErrorState
          error={storesQuery.error}
          onRetry={() => void storesQuery.refetch()}
        />
      )}

      {storesQuery.data !== undefined &&
        (storesQuery.data.content.length === 0 ? (
          <EmptyState
            title="조건에 맞는 매장이 없습니다."
            description="검색어나 운영 상태 조건을 바꿔 다시 조회해 주세요."
          />
        ) : (
          <>
            <div className="po-table-scroll">
              <table className="po-table">
                <caption className="po-table__caption">
                  플랫폼 조회 범위의 매장 정보입니다.
                </caption>
                <thead>
                  <tr>
                    <th scope="col">매장명</th>
                    <th scope="col">매장 ID</th>
                    <th scope="col">운영자 계정</th>
                    <th scope="col">운영 상태</th>
                    <th scope="col">기능 활성</th>
                    <th scope="col">활성 제재</th>
                    <th scope="col">enforcement version</th>
                  </tr>
                </thead>
                <tbody>
                  {storesQuery.data.content.map((store) => (
                    <tr key={store.storeId}>
                      <th scope="row">
                        <Link
                          to={storeDetailPath(store.storeId)}
                          className="po-table__link"
                        >
                          {store.name}
                        </Link>
                      </th>
                      <td>{store.storeId}</td>
                      <td>{store.storeOperatorAccountId}</td>
                      <td>
                        <Badge
                          tone={
                            STORE_OPERATION_STATUS_TONE[store.operationStatus]
                          }
                        >
                          {STORE_OPERATION_STATUS_LABEL[store.operationStatus]}
                        </Badge>
                      </td>
                      <td>{describeEnabledFeatures(store)}</td>
                      <td>
                        {store.activeSanctionTypes.length === 0
                          ? '없음'
                          : store.activeSanctionTypes
                              .map((type) => STORE_SANCTION_TYPE_LABEL[type])
                              .join(', ')}
                      </td>
                      <td>{store.enforcementVersion}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <Pagination
              number={storesQuery.data.page}
              totalPages={storesQuery.data.totalPages}
              totalElements={storesQuery.data.totalElements}
              /*
               * 이 응답에는 hasNext가 없다. 계약이 평면 page 필드만 준다.
               * 마지막 페이지 판정을 서버 값에서 직접 계산한다.
               */
              hasNext={storesQuery.data.page + 1 < storesQuery.data.totalPages}
              onChange={setPage}
            />
          </>
        ))}
    </section>
  )
}

/** 기능 활성 여부를 색이 아닌 문구로 전한다. */
function describeEnabledFeatures(store: {
  reservationEnabled: boolean
  menuHoldEnabled: boolean
  pickupEnabled: boolean
}): string {
  const enabled: string[] = []
  if (store.reservationEnabled) enabled.push('예약')
  if (store.menuHoldEnabled) enabled.push('메뉴 홀드')
  if (store.pickupEnabled) enabled.push('픽업')
  return enabled.length === 0 ? '전부 비활성' : enabled.join(', ')
}
