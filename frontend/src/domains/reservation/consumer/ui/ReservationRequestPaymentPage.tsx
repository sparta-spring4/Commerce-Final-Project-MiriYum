import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import {
  createIdempotencyKey,
  createIdempotencyKeyCache,
} from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { useConfirmCurrentConsumerPayment } from '../../../payment/consumer/api/queries'
import { requestDepositPayment } from '../../../payment/consumer/api/portOneBrowser'
import {
  useAbandonReservationRequest,
  useFinalizeReservationRequest,
  useReservationRequest,
} from '../api/queries'
import { isReservationRequest, type ReservationRequest } from '../model/draft'

const STATUS_MESSAGE: Record<ReservationRequest['status'], string> = {
  AWAITING_PAYMENT: '예약금 결제를 완료하면 예약이 확정됩니다.',
  FINALIZING_RESOURCES: '결제 확인 후 예약 자원을 확정하고 있습니다.',
  COMPLETED: '예약이 확정되었습니다. 완료 화면으로 이동합니다.',
  ABANDONED: '포기된 예약 요청입니다. 다시 결제할 수 없습니다.',
  EXPIRED: '결제 가능 시간이 지난 예약 요청입니다.',
  COMPENSATION_REQUIRED: '결제 후 보상 처리가 필요해 상태를 확인하고 있습니다.',
  COMPENSATING: '결제 보상을 처리하고 있습니다.',
  COMPENSATED: '결제 보상이 완료되어 이 요청으로 예약할 수 없습니다.',
  RECOVERY_REQUIRED: '복구가 필요한 상태입니다. 현재 상태를 확인하고 있습니다.',
}

const CONFIRMATION_HINT_STORAGE_PREFIX =
  'MIRIYUM_RESERVATION_PAYMENT_CONFIRMATION:'
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const TERMINAL_REQUEST_STATUSES: ReadonlySet<ReservationRequest['status']> =
  new Set(['COMPLETED', 'ABANDONED', 'EXPIRED', 'COMPENSATED'])

type LastAction = 'pay' | 'confirm' | 'abandon'

function confirmationHintStorageKey(reservationRequestId: string): string {
  return `${CONFIRMATION_HINT_STORAGE_PREFIX}${reservationRequestId}`
}

function readConfirmationHint(reservationRequestId: string): string | null {
  if (reservationRequestId.length === 0 || typeof window === 'undefined') {
    return null
  }

  try {
    const storageKey = confirmationHintStorageKey(reservationRequestId)
    const value = window.localStorage.getItem(storageKey)
    if (value === null || UUID_PATTERN.test(value)) {
      return value
    }
    window.localStorage.removeItem(storageKey)
  } catch {
    // 브라우저 정책이나 저장 공간 오류가 결제 화면 자체를 막아서는 안 된다.
  }
  return null
}

function writeConfirmationHint(
  reservationRequestId: string,
  confirmationKey: string,
): void {
  if (
    reservationRequestId.length === 0 ||
    !UUID_PATTERN.test(confirmationKey) ||
    typeof window === 'undefined'
  ) {
    return
  }

  try {
    window.localStorage.setItem(
      confirmationHintStorageKey(reservationRequestId),
      confirmationKey,
    )
  } catch {
    // 저장할 수 없는 환경에서도 현재 탭의 확인 흐름은 계속한다.
  }
}

function clearConfirmationHint(reservationRequestId: string): void {
  if (reservationRequestId.length === 0 || typeof window === 'undefined') {
    return
  }

  try {
    window.localStorage.removeItem(
      confirmationHintStorageKey(reservationRequestId),
    )
  } catch {
    // 정리 실패가 서버의 종결 결과를 가리지 않게 한다.
  }
}

export function ReservationRequestPaymentPage() {
  const { reservationRequestId = '' } = useParams()
  const navigate = useNavigate()
  const requestQuery = useReservationRequest(reservationRequestId)
  const request = requestQuery.data
  const paymentId = request?.paymentPreparation.paymentId ?? ''
  const confirmation = useConfirmCurrentConsumerPayment(paymentId)
  const finalization = useFinalizeReservationRequest(reservationRequestId)
  const abandonment = useAbandonReservationRequest(reservationRequestId)
  const finalizationKeys = useMemo(createIdempotencyKeyCache, [])
  const abandonmentKeys = useMemo(createIdempotencyKeyCache, [])
  const [actionMessage, setActionMessage] = useState<string | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  const [confirmationKey, setConfirmationKey] = useState<string | null>(() =>
    readConfirmationHint(reservationRequestId),
  )
  const [lastAction, setLastAction] = useState<LastAction | null>(null)

  useEffect(() => {
    if (
      request !== undefined &&
      (request.abandonmentRequested ||
        TERMINAL_REQUEST_STATUSES.has(request.status))
    ) {
      clearConfirmationHint(reservationRequestId)
      setConfirmationKey(null)
    }
    if (
      request?.status === 'COMPLETED' &&
      request.reservation?.status === 'CONFIRMED'
    ) {
      void navigate(
        `/reservations/${request.reservation.reservationId}/complete`,
        { replace: true },
      )
    }
  }, [navigate, request, reservationRequestId])

  if (requestQuery.isPending) {
    return (
      <div className="mi-container mi-container--narrow">
        <Loading label="예약금 결제 정보를 불러오는 중입니다." />
      </div>
    )
  }

  if (requestQuery.isError) {
    return (
      <div className="mi-container mi-container--narrow">
        <ErrorState
          error={requestQuery.error}
          onRetry={() => void requestQuery.refetch()}
        />
      </div>
    )
  }

  const paymentPreparation = requestQuery.data.paymentPreparation
  const canPay =
    requestQuery.data.status === 'AWAITING_PAYMENT' &&
    !requestQuery.data.abandonmentRequested
  const statusMessage =
    requestQuery.data.status === 'AWAITING_PAYMENT' &&
    requestQuery.data.abandonmentRequested
      ? '예약 요청 포기가 접수되어 이 요청으로는 더 이상 결제할 수 없습니다.'
      : STATUS_MESSAGE[requestQuery.data.status]
  const isWorking =
    confirmation.isPending || finalization.isPending || abandonment.isPending

  async function confirmAndFinalize(idempotencyKey: string) {
    setLastAction('confirm')
    setActionMessage(null)
    setActionError(null)

    try {
      const confirmedPayment = await confirmation.mutateAsync({
        portOnePaymentId: paymentPreparation.portOnePaymentId,
        idempotencyKey,
      })
      if (confirmedPayment.status !== 'PAID') {
        setActionMessage(
          confirmedPayment.status === 'RECONCILIATION_REQUIRED'
            ? '결제 결과를 확인 중입니다. 잠시 후 상태를 다시 확인해 주세요.'
            : '결제가 확정되지 않았습니다. 현재 상태를 다시 확인해 주세요.',
        )
        await requestQuery.refetch()
        return
      }

      const result = await finalization.mutateAsync({
        idempotencyKey: finalizationKeys.keyFor(reservationRequestId),
      })
      if (isReservationRequest(result)) {
        await requestQuery.refetch()
        return
      }
      if (result.status !== 'CONFIRMED') {
        setActionMessage('예약 확정 결과를 확인하지 못했습니다.')
        return
      }
      clearConfirmationHint(reservationRequestId)
      setConfirmationKey(null)
      void navigate(`/reservations/${result.reservationId}/complete`, {
        replace: true,
      })
    } catch (error) {
      setActionError(error)
    }
  }

  async function pay() {
    setLastAction('pay')
    setActionMessage(null)
    setActionError(null)

    try {
      const sdkResult = await requestDepositPayment(paymentPreparation)
      if (sdkResult === undefined) {
        setActionMessage('결제창이 닫혔습니다. 결제를 다시 시도할 수 있습니다.')
        return
      }
      if (sdkResult.code !== undefined) {
        setActionMessage(
          sdkResult.message ?? '결제가 완료되지 않았습니다. 다시 시도해 주세요.',
        )
        return
      }

      const nextConfirmationKey = createIdempotencyKey()
      writeConfirmationHint(reservationRequestId, nextConfirmationKey)
      setConfirmationKey(nextConfirmationKey)
      await confirmAndFinalize(nextConfirmationKey)
    } catch (error) {
      setActionError(error)
    }
  }

  async function abandon() {
    setLastAction('abandon')
    setActionMessage(null)
    setActionError(null)
    try {
      await abandonment.mutateAsync({
        idempotencyKey: abandonmentKeys.keyFor(reservationRequestId),
      })
      clearConfirmationHint(reservationRequestId)
      setConfirmationKey(null)
    } catch (error) {
      setActionError(error)
    }
  }

  async function retryLastAction() {
    if (lastAction === 'pay') {
      await pay()
      return
    }
    if (lastAction === 'confirm' && confirmationKey !== null) {
      await confirmAndFinalize(confirmationKey)
      return
    }
    if (lastAction === 'abandon') {
      await abandon()
      return
    }
    await requestQuery.refetch()
  }

  return (
    <main className="mi-container mi-container--narrow reservation-payment">
      <header className="mi-page-head">
        <p className="mi-page-head__eyebrow">예약금 결제</p>
        <h1 className="mi-page-head__title">
          예약 확정을 위한 결제가 필요합니다
        </h1>
      </header>

      <section className="mi-card reservation-payment__summary">
        <dl>
          <div>
            <dt>주문명</dt>
            <dd>{paymentPreparation.orderName}</dd>
          </div>
          <div>
            <dt>예약금</dt>
            <dd>{`${paymentPreparation.amountMinor.toLocaleString('ko-KR')}원`}</dd>
          </div>
          <div>
            <dt>결제 기한</dt>
            <dd>
              {new Date(requestQuery.data.expiresAt).toLocaleString('ko-KR')}
            </dd>
          </div>
        </dl>
      </section>

      <Alert
        tone={canPay ? 'info' : 'warning'}
        title={statusMessage}
      />
      {actionMessage && <Alert tone="warning" title={actionMessage} />}
      {actionError !== null && (
        <ErrorState
          error={actionError}
          message="결제 처리 중 문제가 발생했습니다. 서버 상태를 다시 확인해 주세요."
          onRetry={() => void retryLastAction()}
        />
      )}

      <div className="reservation-payment__actions">
        <Button
          size="lg"
          block
          disabled={!canPay}
          loading={isWorking}
          onClick={() =>
            void (confirmationKey !== null
              ? confirmAndFinalize(confirmationKey)
              : pay())
          }
        >
          {confirmationKey !== null ? '결제 상태 다시 확인' : '예약금 결제하기'}
        </Button>
        {canPay && (
          <Button
            variant="ghost"
            block
            disabled={isWorking}
            onClick={() => void abandon()}
          >
            예약 요청 포기
          </Button>
        )}
        <Link
          className="mi-button mi-button--ghost mi-button--block"
          to={CONSUMER_PATHS.myReservations}
        >
          내 예약으로 돌아가기
        </Link>
      </div>
    </main>
  )
}
