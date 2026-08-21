import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { PageHeader } from '../../../../app/shells/store-operator/OperatorPage'
import { Badge } from '../../../../shared/ui/Badge'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Pagination } from '../../../../shared/ui/Pagination'
import { useStorePickupReservations } from '../api/queries'
import { pickupOperatorErrorMessage } from '../model/errors'
import { PICKUP_STATUS_LABEL, type PickupStatus } from '../model/types'

export function PickupReservationsPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)
  const [pickupDate, setPickupDate] = useState('')
  const [status, setStatus] = useState<PickupStatus | ''>('')
  const [page, setPage] = useState(0)
  const query = useStorePickupReservations(storeId, {
    pickupDate: pickupDate || undefined,
    status: status || undefined,
    page,
    size: 20,
    sort: 'pickupDate,asc',
  })

  return (
    <>
      <PageHeader title="픽업 목록" description="현재 매장의 픽업 예약을 조회하고 상세 처리합니다." />
      <section className="mi-card"><div className="mi-card__body">
        <div className="op-form-grid">
          <TextField label="픽업 날짜" type="date" value={pickupDate} onChange={(event) => { setPickupDate(event.target.value); setPage(0) }} />
          <SelectField label="상태" value={status} onChange={(event) => { setStatus(event.target.value as PickupStatus | ''); setPage(0) }}>
            <option value="">전체</option>
            {Object.entries(PICKUP_STATUS_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </SelectField>
        </div>
        {query.isPending && <Loading label="픽업 예약을 불러오는 중입니다." />}
        {query.isError && <ErrorState error={query.error} message={pickupOperatorErrorMessage(query.error)} onRetry={() => void query.refetch()} />}
        {query.isSuccess && query.data.items.length === 0 && <EmptyState title="조건에 맞는 픽업 예약이 없습니다." />}
        {query.isSuccess && query.data.items.length > 0 && (
          <>
            <div className="op-table-scroll"><table className="op-table"><caption className="visually-hidden">픽업 예약 목록</caption>
              <thead><tr><th>상태</th><th>픽업 일시</th><th>메뉴</th><th>관리</th></tr></thead>
              <tbody>{query.data.items.map((pickup) => (
                <tr key={pickup.pickupReservationId}>
                  <td><Badge tone={pickup.status === 'CONFIRMED' ? 'attention' : pickup.status === 'PICKED_UP' ? 'positive' : 'neutral'}>{PICKUP_STATUS_LABEL[pickup.status]}</Badge></td>
                  <td>{pickup.pickupDate} {pickup.pickupTime.slice(0, 5)}</td>
                  <td>{pickup.items.map((item) => `${item.menuName} × ${item.quantity}`).join(', ')}</td>
                  <td><Link className="mi-button mi-button--ghost mi-button--sm" aria-label={`픽업 ${pickup.pickupReservationId} 상세`} to={fillPath(STORE_OPERATOR_PATHS.pickupReservation, { storeId, pickupReservationId: pickup.pickupReservationId })}>상세</Link></td>
                </tr>
              ))}</tbody>
            </table></div>
            <Pagination {...query.data.page} unitLabel="건" onChange={setPage} />
          </>
        )}
      </div></section>
    </>
  )
}
