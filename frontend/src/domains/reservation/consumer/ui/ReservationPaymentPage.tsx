import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { Button } from '../../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { formatPrice } from '../../../store/public/model/labels'
import {
  portOnePaymentGateway,
  type PaymentGateway,
} from '../api/paymentGateway'
import {
  useConfirmReservationPayment,
  useFinalizeReservationRequest,
  useReservationRequest,
} from '../api/paymentQueries'
import { isReservationRequest } from '../model/draft'

export interface PortOneConfig {
  storeId: string
  channelKey: string
}

function configuredPortOne(): PortOneConfig | null {
  const storeId = import.meta.env.VITE_PORTONE_STORE_ID?.trim()
  const channelKey = import.meta.env.VITE_PORTONE_CHANNEL_KEY?.trim()
  return storeId && channelKey ? { storeId, channelKey } : null
}

type FlowState = 'ready' | 'gateway-failed' | 'reconciling' | 'failed'

/** 예약금 결제 전용 화면. 기존 예약 작성 화면은 진입 링크 외에 재구성하지 않는다. */
export function ReservationPaymentPage({
  gateway = portOnePaymentGateway,
  portOneConfig = configuredPortOne(),
}: {
  gateway?: PaymentGateway
  portOneConfig?: PortOneConfig | null
}) {
  const { reservationRequestId = '' } = useParams()
  const navigate = useNavigate()
  const request = useReservationRequest(reservationRequestId)
  const confirmation = useConfirmReservationPayment()
  const finalization = useFinalizeReservationRequest(reservationRequestId)
  const [state, setState] = useState<FlowState>('ready')
  const [message, setMessage] = useState<string | null>(null)

  if (request.isPending) {
    return <Loading label="결제 정보를 불러오는 중입니다." />
  }
  if (request.isError || request.data === undefined) {
    return <ErrorState error={request.error} onRetry={() => void request.refetch()} />
  }

  const process = request.data
  const preparation = process.paymentPreparation
  const busy = confirmation.isPending || finalization.isPending
  const payable = process.status === 'AWAITING_PAYMENT' && !process.abandonmentRequested

  async function pay() {
    if (portOneConfig === null || !payable) return
    setMessage(null)
    setState('ready')

    try {
      const gatewayResult = await gateway.requestPayment({
        ...portOneConfig,
        paymentId: preparation.portOnePaymentId,
        orderName: preparation.orderName,
        totalAmount: preparation.amountMinor,
        currency: preparation.currency,
      })
      if (gatewayResult.kind === 'failed') {
        setState('gateway-failed')
        setMessage(gatewayResult.message)
        return
      }

      const payment = await confirmation.mutateAsync({
        paymentId: preparation.paymentId,
        portOnePaymentId: gatewayResult.paymentId,
      })
      if (payment.status === 'RECONCILIATION_REQUIRED') {
        setState('reconciling')
        setMessage('결제 결과를 확인 중입니다. 추가 결제를 시도하지 마세요.')
        return
      }
      if (payment.status !== 'PAID') {
        setState('failed')
        setMessage('결제가 완료되지 않았습니다. 결제 상태를 다시 확인해 주세요.')
        return
      }

      const result = await finalization.mutateAsync()
      if (isReservationRequest(result)) {
        setState('reconciling')
        setMessage('결제는 확인됐고 예약을 확정하는 중입니다. 잠시 후 상태를 다시 확인해 주세요.')
        void request.refetch()
        return
      }
      void navigate(
        CONSUMER_PATHS.reservationComplete.replace(':reservationId', result.reservationId),
        { replace: true },
      )
    } catch {
      setState('reconciling')
      setMessage('처리 결과를 확인하지 못했습니다. 중복 결제하지 말고 상태를 다시 확인해 주세요.')
    }
  }

  return (
    <main className="mi-container mi-container--narrow reservation-payment">
      <header className="mi-page-head">
        <p className="reservation-detail__breadcrumb">
          <Link to={CONSUMER_PATHS.myReservations}>내 예약으로 돌아가기</Link>
        </p>
        <h1 className="mi-page-head__title">예약금 결제</h1>
        <p className="mi-page-head__lead">결제가 확인되어야 예약이 최종 확정됩니다.</p>
      </header>

      {portOneConfig === null && payable && (
        <Alert tone="warning" title="결제 서비스 설정이 아직 완료되지 않았습니다." />
      )}
      {message !== null && (
        <Alert tone={state === 'reconciling' ? 'warning' : 'error'} title={message} />
      )}
      {!payable && process.status !== 'COMPLETED' && (
        <Alert tone="warning" title="현재 이 예약금 요청은 결제할 수 없습니다." />
      )}
      {process.status === 'COMPLETED' && process.reservation !== null && (
        <Alert
          tone="info"
          title="이미 예약이 확정되었습니다."
          actions={
            <Link
              className="mi-button mi-button--ghost mi-button--sm"
              to={CONSUMER_PATHS.reservationDetail.replace(':reservationId', process.reservation.reservationId)}
            >
              예약 보기
            </Link>
          }
        />
      )}

      <section className="mi-card mi-card--roomy reservation-payment__card">
        <div className="mi-card__body mi-card__body--roomy">
          <h2>{preparation.orderName}</h2>
          <dl className="reservation-payment__summary">
            <div><dt>결제 금액</dt><dd>{formatPrice(preparation.amountMinor)}</dd></div>
            <div><dt>통화</dt><dd>{preparation.currency}</dd></div>
            <div><dt>결제 기한</dt><dd>{new Date(process.expiresAt).toLocaleString('ko-KR')}</dd></div>
          </dl>
          <p className="reservation-payment__notice">
            화면에 표시된 금액은 서버가 예약 생성 시 확정한 값이며 결제창에서 임의로 변경하지 않습니다.
          </p>
          <Button
            size="lg"
            block
            loading={busy}
            disabled={!payable || portOneConfig === null || state === 'reconciling'}
            onClick={() => void pay()}
          >
            {portOneConfig === null
              ? '결제 준비 중'
              : state === 'gateway-failed'
                ? '다시 결제하기'
                : `${formatPrice(preparation.amountMinor)} 결제하기`}
          </Button>
          {state === 'reconciling' && (
            <Button variant="ghost" block onClick={() => void request.refetch()}>
              결제 상태 다시 확인
            </Button>
          )}
        </div>
      </section>
    </main>
  )
}
