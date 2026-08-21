import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { fillPath } from '../../../../app/routes/path'
import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { PageHeader, SectionCard } from '../../../../app/shells/store-operator/OperatorPage'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import { useCheckInReservationByQr, useMarkReservationNoShow } from '../api/queries'
import { reservationOpsErrorMessage } from '../model/errors'
import { RESERVATION_NO_SHOW_REASON_LABEL, type ReservationDetail, type ReservationNoShowReason } from '../model/types'

const QR_PATTERN = /^rqg_v1_[A-Za-z0-9_-]{43}$/

export function ReservationVisitOperationsPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)
  const checkIn = useCheckInReservationByQr(storeId)
  const [reservationId, setReservationId] = useState('')
  const noShow = useMarkReservationNoShow(storeId, reservationId.trim())
  const checkInKeys = useMemo(createIdempotencyKeyCache, [])
  const noShowKeys = useMemo(createIdempotencyKeyCache, [])
  const [qrToken, setQrToken] = useState('')
  const [reason, setReason] = useState<ReservationNoShowReason | ''>('')
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ReservationDetail | null>(null)

  async function submitCheckIn() {
    const token = qrToken.trim()
    if (!QR_PATTERN.test(token)) { setError('고객의 최신 체크인 QR을 다시 스캔해 주세요.'); return }
    setError(null); setResult(null)
    try { setResult(await checkIn.mutateAsync({ body: { qrToken: token }, idempotencyKey: checkInKeys.keyFor(token) })) }
    catch (caught) { setError(reservationOpsErrorMessage(caught)) }
  }

  function reviewNoShow() {
    if (reservationId.trim().length === 0) { setError('예약 ID를 입력해 주세요.'); return }
    if (reason === '') { setError('노쇼 후보 사유를 선택해 주세요.'); return }
    setError(null); setConfirming(true)
  }

  async function submitNoShow() {
    if (reason === '') return
    const id = reservationId.trim()
    setError(null); setResult(null)
    try {
      setResult(await noShow.mutateAsync({ reason, idempotencyKey: noShowKeys.keyFor(JSON.stringify({ id, reason })) }))
      setConfirming(false)
    } catch (caught) { setError(reservationOpsErrorMessage(caught)) }
  }

  return <>
    <PageHeader title="예약 체크인·노쇼 처리" description="고객 QR 체크인과 미방문 예약 확정을 한 화면에서 처리합니다." />
    {error && <Alert tone="error" title={error} />}
    {result && <Alert tone="info" title={`예약 ${result.reservationId} ${result.status === 'FULFILLED' ? '방문 완료' : '노쇼 확정'}`} actions={<Link className="mi-button mi-button--ghost mi-button--sm" to={fillPath(STORE_OPERATOR_PATHS.reservation, { storeId, reservationId: result.reservationId })}>예약 상세</Link>} />}
    <div className="op-stack">
      <SectionCard title="QR 체크인" hint="고객 화면의 최신 회전형 QR을 스캔하거나 스캔 값을 붙여 넣으세요.">
        <TextField label="QR 스캔 값" value={qrToken} autoComplete="off" onChange={(event) => setQrToken(event.target.value)} />
        <div className="op-actions"><Button loading={checkIn.isPending} onClick={() => void submitCheckIn()}>QR 체크인 완료</Button></div>
      </SectionCard>
      <SectionCard title="노쇼 확정" hint="예약 시작 5분 후부터 가능하며 서버가 시간 경계를 최종 판정합니다.">
        <div className="op-form-grid">
          <TextField label="예약 ID" value={reservationId} onChange={(event) => { setReservationId(event.target.value); setConfirming(false) }} />
          <SelectField label="노쇼 후보 사유" value={reason} onChange={(event) => { setReason(event.target.value as ReservationNoShowReason | ''); setConfirming(false) }}><option value="">선택</option>{(Object.entries(RESERVATION_NO_SHOW_REASON_LABEL) as [ReservationNoShowReason, string][]).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
        </div>
        {confirming ? <><Alert tone="warning" title={`예약 ${reservationId.trim()}을 노쇼로 확정합니다.`}>확정 후 되돌릴 수 없습니다.</Alert><div className="op-actions"><Button variant="danger" loading={noShow.isPending} onClick={() => void submitNoShow()}>노쇼로 확정</Button><Button variant="ghost" onClick={() => setConfirming(false)}>그만두기</Button></div></> : <div className="op-actions"><Button variant="danger" onClick={reviewNoShow}>노쇼 확정 검토</Button></div>}
      </SectionCard>
    </div>
  </>
}
