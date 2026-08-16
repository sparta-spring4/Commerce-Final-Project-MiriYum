import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ROUTES } from '../../../app/routes'
import { hasErrorCode } from '../../../shared/api/apiError'
import { useIdempotentAttempt } from '../../../shared/api/useIdempotentAttempt'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Icon } from '../../../shared/ui/Icon'
import { formatPrice } from '../../store-search/model/labels'
import {
  useCancelPickupReservation,
  usePickupReservation,
} from '../api/queries'
import {
  PICKUP_STATUS_LABEL,
  PICKUP_STATUS_TONE,
  PickupErrorCode,
  pickupTotalPrice,
  toPickupCancelMessage,
} from '../model/pickup'

/**
 * 픽업 예약 상세와 취소.
 *
 * 일반 사용자 픽업 목록 계약이 없으므로 목록으로 돌아가는 경로를 만들지 않는다.
 * 생성 직후 받은 식별자로만 이 화면에 들어온다.
 */
export function PickupDetailPage() {
  const { pickupReservationId = '' } = useParams()
  const detail = usePickupReservation(pickupReservationId)

  if (detail.isPending) {
    return (
      <div className="mi-container mi-container--narrow pickup-detail">
        <Loading label="픽업 예약을 불러오는 중입니다." />
      </div>
    )
  }

  if (detail.isError) {
    const notFound = hasErrorCode(detail.error, PickupErrorCode.NOT_FOUND)
    return (
      <div className="mi-container mi-container--narrow pickup-detail">
        <ErrorState
          error={detail.error}
          message={notFound ? '픽업 예약을 찾을 수 없습니다.' : undefined}
          onRetry={notFound ? undefined : () => void detail.refetch()}
        />
        <p>
          <Link to={ROUTES.stores}>매장 찾기로 돌아가기</Link>
        </p>
      </div>
    )
  }

  const reservation = detail.data

  return (
    <div className="mi-container mi-container--narrow pickup-detail">
      <header className="pickup-detail__header">
        <Badge tone={PICKUP_STATUS_TONE[reservation.status]}>
          {PICKUP_STATUS_LABEL[reservation.status]}
        </Badge>
        <h1>{reservation.storeName}</h1>
        <p className="pickup-detail__meta">
          <Icon name="bag" />
          {`${reservation.pickupDate} ${reservation.pickupTime} 픽업`}
        </p>
      </header>

      <section
        className="mi-card mi-card--roomy pickup-detail__section"
        aria-label="픽업 내용"
      >
        <div className="mi-card__body mi-card__body--roomy">
          <h2>주문 내역</h2>
          <ul className="pickup-detail__items">
            {reservation.items.map((item) => (
              <li key={item.menuId}>
                <span>{`${item.menuName} x ${item.quantity}`}</span>
                <span className="pickup-detail__amount">
                  {formatPrice(item.unitPrice * item.quantity)}
                </span>
              </li>
            ))}
          </ul>
          <p className="pickup-detail__total">
            <span>합계</span>
            <span>{formatPrice(pickupTotalPrice(reservation))}</span>
          </p>

          {reservation.status === 'CANCELLED' && (
            <dl className="pickup-detail__list">
              <dt>취소 주체</dt>
              <dd>
                {reservation.cancelledBy === 'CONSUMER'
                  ? '내가 취소'
                  : reservation.cancelledBy === 'STORE_OPERATOR'
                    ? '매장이 취소'
                    : '확인할 수 없음'}
              </dd>
              <dt>취소 사유</dt>
              <dd>{reservation.cancellationReason ?? '사유 없음'}</dd>
            </dl>
          )}

          <p className="pickup-detail__store-link">
            <Link to={`/stores/${reservation.storeId}`}>
              매장 정보 보기
              <Icon name="arrowRight" className="mi-icon--sm" />
            </Link>
          </p>
        </div>
      </section>

      {reservation.status === 'CONFIRMED' && (
        <CancelSection pickupReservationId={pickupReservationId} />
      )}
    </div>
  )
}

function CancelSection({
  pickupReservationId,
}: {
  pickupReservationId: string
}) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  /*
   * 취소 사유는 요청 본문이라 요청 지문의 일부다. 예약 취소와 같은 규칙을 쓴다.
   * 사유가 바뀌면 새 키, 결과 불명 뒤 사유 변경은 차단이다.
   */
  const normalizedReason = reason.trim()
  const attempt = useIdempotentAttempt(normalizedReason)

  const mutation = useCancelPickupReservation(pickupReservationId)

  function handleCancel() {
    setError(null)

    const idempotencyKey = attempt.begin()
    if (idempotencyKey === null) {
      setError(
        '앞선 취소 요청의 처리 여부를 확인하지 못했습니다. 사유를 바꿔 다시 보내면 취소가 두 번 처리될 수 있습니다. 최신 상태를 먼저 확인해 주세요.',
      )
      return
    }

    mutation.mutate(
      { reason: normalizedReason || undefined, idempotencyKey },
      {
        onSuccess: () => {
          attempt.settle(null)
          setOpen(false)
        },
        onError: (cause) => {
          attempt.settle(cause)
          setError(toPickupCancelMessage(cause))
        },
      },
    )
  }

  if (!open) {
    return (
      <section className="pickup-detail__section">
        <Button variant="danger" onClick={() => setOpen(true)}>
          픽업 예약 취소하기
        </Button>
      </section>
    )
  }

  return (
    <section className="mi-card pickup-detail__section" aria-label="픽업 예약 취소">
      <div className="mi-card__body">
        <h2>픽업 예약을 취소할까요?</h2>
        {error !== null && <Alert tone="error" title={error} />}

        <label className="mi-field">
          <span className="mi-field__label">취소 사유 (선택)</span>
          <textarea
            className="mi-field__control pickup-detail__reason"
            name="reason"
            maxLength={500}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>

        <div className="pickup-detail__actions">
          <Button
            variant="danger"
            loading={mutation.isPending}
            onClick={handleCancel}
          >
            취소 확정
          </Button>
          <Button
            variant="ghost"
            disabled={mutation.isPending}
            onClick={() => setOpen(false)}
          >
            돌아가기
          </Button>
        </div>
      </div>
    </section>
  )
}
