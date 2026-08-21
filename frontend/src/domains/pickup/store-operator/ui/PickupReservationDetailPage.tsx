import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { PageHeader, SectionCard, SummaryList } from '../../../../app/shells/store-operator/OperatorPage'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { TextField } from '../../../../shared/ui/Field'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { useCancelStorePickup, useFulfillStorePickup, useStorePickupReservation } from '../api/queries'
import { pickupOperatorErrorMessage } from '../model/errors'
import { PICKUP_STATUS_LABEL, type PickupReservation } from '../model/types'

export function PickupReservationDetailPage() {
  const { storeId = '', pickupReservationId = '' } = useParams<{ storeId: string; pickupReservationId: string }>()
  useAdoptStoreFromRoute(storeId)
  const query = useStorePickupReservation(storeId, pickupReservationId)
  return (
    <>
      <PageHeader title="픽업 예약 상세" description={`픽업 예약 #${pickupReservationId}`} />
      {query.isPending && <Loading label="픽업 예약을 불러오는 중입니다." />}
      {query.isError && <ErrorState error={query.error} message={pickupOperatorErrorMessage(query.error)} onRetry={() => void query.refetch()} />}
      {query.data && <PickupDetail storeId={storeId} pickup={query.data} />}
    </>
  )
}

function PickupDetail({ storeId, pickup }: { storeId: string; pickup: PickupReservation }) {
  return (
    <div className="op-page-stack">
      <SectionCard title="예약 정보">
        <SummaryList items={[
          { term: '상태', value: <Badge tone={pickup.status === 'CONFIRMED' ? 'attention' : pickup.status === 'PICKED_UP' ? 'positive' : 'neutral'}>{PICKUP_STATUS_LABEL[pickup.status]}</Badge> },
          { term: '픽업 일시', value: `${pickup.pickupDate} ${pickup.pickupTime.slice(0, 5)}` },
          { term: '매장', value: pickup.storeName },
          { term: '신청 시각', value: new Date(pickup.createdAt).toLocaleString('ko-KR') },
        ]} />
        <div className="op-table-scroll"><table className="op-table"><caption className="visually-hidden">픽업 메뉴</caption><thead><tr><th>메뉴</th><th>수량</th><th>금액</th></tr></thead><tbody>
          {pickup.items.map((item) => <tr key={item.menuId}><th>{item.menuName}</th><td>{item.quantity}</td><td>{(item.unitPrice * item.quantity).toLocaleString('ko-KR')}원</td></tr>)}
        </tbody></table></div>
        {pickup.cancellationReason && <Alert tone="info" title="취소 사유"><p>{pickup.cancellationReason}</p></Alert>}
      </SectionCard>
      <PickupCommands storeId={storeId} pickup={pickup} />
    </div>
  )
}

function PickupCommands({ storeId, pickup }: { storeId: string; pickup: PickupReservation }) {
  const fulfill = useFulfillStorePickup(storeId, pickup.pickupReservationId)
  const cancel = useCancelStorePickup(storeId, pickup.pickupReservationId)
  const fulfillKeys = useMemo(createIdempotencyKeyCache, [])
  const cancelKeys = useMemo(createIdempotencyKeyCache, [])
  const [confirmFulfill, setConfirmFulfill] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  if (pickup.status !== 'CONFIRMED') {
    return <SectionCard title="픽업 처리"><Alert tone="info" title={`${PICKUP_STATUS_LABEL[pickup.status]} 상태입니다.`}>완료된 픽업은 이 화면에서 되돌리지 않습니다.</Alert></SectionCard>
  }

  async function handleFulfill() {
    setError(null)
    try {
      await fulfill.mutateAsync(fulfillKeys.keyFor(pickup.pickupReservationId))
      setConfirmFulfill(false)
    } catch (caught) { setError(pickupOperatorErrorMessage(caught)) }
  }

  async function handleCancel() {
    const trimmed = reason.trim()
    if (!trimmed) { setError('취소 사유를 입력해 주세요.'); return }
    setError(null)
    try {
      await cancel.mutateAsync({ reason: trimmed, idempotencyKey: cancelKeys.keyFor(JSON.stringify({ id: pickup.pickupReservationId, reason: trimmed })) })
    } catch (caught) { setError(pickupOperatorErrorMessage(caught)) }
  }

  return (
    <SectionCard title="픽업 처리" hint="수령 완료와 취소는 되돌릴 수 없습니다.">
      {error && <Alert tone="error" title={error} />}
      <div className="op-day__group">
        <p className="op-day__group-title">수령 완료</p>
        {confirmFulfill ? <div className="op-actions"><Button variant="secondary" loading={fulfill.isPending} onClick={() => void handleFulfill()}>수령 완료로 확정</Button><Button variant="ghost" onClick={() => setConfirmFulfill(false)}>그만두기</Button></div> : <Button variant="secondary" onClick={() => setConfirmFulfill(true)}>수령 완료 처리</Button>}
      </div>
      <div className="op-day__group">
        <TextField label="취소 사유" required maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} />
        <Button variant="danger" loading={cancel.isPending} onClick={() => void handleCancel()}>픽업 예약 취소</Button>
      </div>
    </SectionCard>
  )
}
