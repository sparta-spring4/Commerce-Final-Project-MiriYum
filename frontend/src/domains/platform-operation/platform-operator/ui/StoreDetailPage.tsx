import { PLATFORM_OPERATOR_PATHS } from '../../../../app/routes/paths/platformOperatorPaths'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { isApiError } from '../../../../shared/api/apiError'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../../account/platform-operator/auth'
import type { AuditReason } from '../api/operatorAccountApi'
import {
  fetchStore,
  storeQueryKeys,
  type AdminStoreDetail,
} from '../api/storeAdminApi'
import {
  STORE_FEATURE_LABEL,
  STORE_OPERATION_STATUS_LABEL,
  STORE_OPERATION_STATUS_TONE,
  STORE_SANCTION_TYPE_LABEL,
} from '../model/storeLabels'
import { storeSanctionCasePath } from '../model/paths'
import { AdminReasonGate } from './AdminReasonGate'
import { OperatorAccessDenied } from './OperatorAccessDenied'
import { StoreSanctionCaseCreateForm } from './StoreSanctionCaseCreateForm'
import './page.css'

/**
 * 매장 상세.
 *
 * 계약이 주는 것만 보여 준다. 대표자 이름·사업자번호·연락처·매출은 응답에
 * 없으므로 만들지 않는다.
 *
 * `openCaseIds`는 진행 중인 제재 사건의 ID 목록이다. 사건 상세를 열려면
 * 사건 version과 사유가 더 필요하므로 여기서는 ID만 보여 주고 이동시킨다.
 */
export function StoreDetailPage() {
  const { capabilities } = usePlatformOperatorAuth()
  const params = useParams<{ storeId: string }>()
  const [reasonCode, setReasonCode] = useState<AuditReason | null>(null)

  const storeId = Number(params.storeId)
  const validStoreId = Number.isInteger(storeId) && storeId > 0

  if (decideCapability(capabilities, 'STORE_READ_MINIMAL') === 'denied') {
    return <OperatorAccessDenied />
  }

  if (!validStoreId) {
    return (
      <EmptyState
        title="잘못된 주소입니다."
        description="매장 목록에서 다시 선택해 주세요."
      />
    )
  }

  if (reasonCode === null) {
    return (
      <AdminReasonGate
        heading="매장 상세"
        subtitle="조회 사유를 먼저 지정합니다."
        onSubmit={setReasonCode}
      />
    )
  }

  return (
    <StoreDetailBody
      storeId={storeId}
      reasonCode={reasonCode}
      onChangeReason={() => setReasonCode(null)}
    />
  )
}

function StoreDetailBody({
  storeId,
  reasonCode,
  onChangeReason,
}: {
  storeId: number
  reasonCode: AuditReason
  onChangeReason: () => void
}) {
  const { apiClient, capabilities } = usePlatformOperatorAuth()
  const canSanction =
    decideCapability(capabilities, 'STORE_SANCTION') === 'allowed'

  const storeQuery = useQuery({
    queryKey: storeQueryKeys.detail(reasonCode, storeId),
    queryFn: ({ signal }) => fetchStore(apiClient, reasonCode, storeId, signal),
    enabled: decideCapability(capabilities, 'STORE_READ_MINIMAL') === 'allowed',
  })

  const denied =
    storeQuery.isError && isApiError(storeQuery.error)
      ? storeQuery.error.status === 403
      : false

  return (
    <section aria-labelledby="store-detail-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="store-detail-heading">
            매장 상세
          </h1>
          <p className="po-page__subtitle">
            <Link to={PLATFORM_OPERATOR_PATHS.stores} className="po-table__link">
              매장 목록으로
            </Link>
          </p>
        </div>
        <Button type="button" variant="ghost" onClick={onChangeReason}>
          조회 사유 변경
        </Button>
      </header>

      {storeQuery.isPending && (
        <Loading label="매장 정보를 불러오는 중입니다." />
      )}

      {denied && <OperatorAccessDenied />}

      {storeQuery.isError && !denied && (
        <ErrorState
          error={storeQuery.error}
          onRetry={() => void storeQuery.refetch()}
        />
      )}

      {storeQuery.data !== undefined && (
        <>
          <StoreSummary store={storeQuery.data} />
          <OpenCaseList store={storeQuery.data} />
          {canSanction && (
            <StoreSanctionCaseCreateForm
              storeId={storeId}
              reasonCode={reasonCode}
              onCreated={() => void storeQuery.refetch()}
            />
          )}
        </>
      )}
    </section>
  )
}

function StoreSummary({ store }: { store: AdminStoreDetail }) {
  return (
    <dl className="po-detail">
      <div className="po-detail__row">
        <dt>매장명</dt>
        <dd>{store.name}</dd>
      </div>
      <div className="po-detail__row">
        <dt>매장 ID</dt>
        <dd>{store.storeId}</dd>
      </div>
      <div className="po-detail__row">
        <dt>운영자 계정 ID</dt>
        <dd>{store.storeOperatorAccountId}</dd>
      </div>
      <div className="po-detail__row">
        <dt>운영 상태</dt>
        <dd>
          <Badge tone={STORE_OPERATION_STATUS_TONE[store.operationStatus]}>
            {STORE_OPERATION_STATUS_LABEL[store.operationStatus]}
          </Badge>
        </dd>
      </div>
      <div className="po-detail__row">
        <dt>기능 활성</dt>
        <dd>
          <ul className="po-inline-list">
            <li>
              {STORE_FEATURE_LABEL.RESERVATION} ·{' '}
              {store.reservationEnabled ? '활성' : '비활성'}
            </li>
            <li>
              {STORE_FEATURE_LABEL.MENU_HOLD} ·{' '}
              {store.menuHoldEnabled ? '활성' : '비활성'}
            </li>
            <li>
              {STORE_FEATURE_LABEL.PICKUP} ·{' '}
              {store.pickupEnabled ? '활성' : '비활성'}
            </li>
          </ul>
        </dd>
      </div>
      <div className="po-detail__row">
        <dt>활성 제재</dt>
        <dd>
          {store.activeSanctionTypes.length === 0
            ? '없음'
            : store.activeSanctionTypes
                .map((type) => STORE_SANCTION_TYPE_LABEL[type])
                .join(', ')}
        </dd>
      </div>
      <div className="po-detail__row">
        <dt>enforcement version</dt>
        <dd>{store.enforcementVersion}</dd>
      </div>
      <div className="po-detail__row">
        <dt>생성일</dt>
        <dd>{formatDate(store.createdAt)}</dd>
      </div>
    </dl>
  )
}

function OpenCaseList({ store }: { store: AdminStoreDetail }) {
  return (
    <section aria-labelledby="open-cases-heading">
      <h2 className="po-section__title" id="open-cases-heading">
        진행 중인 제재 사건
      </h2>
      {store.openCaseIds.length === 0 ? (
        <EmptyState title="진행 중인 제재 사건이 없습니다." />
      ) : (
        <ul className="po-list">
          {store.openCaseIds.map((caseId) => (
            <li key={caseId}>
              <Link
                to={storeSanctionCasePath(store.storeId, caseId)}
                className="po-table__link"
              >
                {caseId}
              </Link>
            </li>
          ))}
        </ul>
      )}
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
