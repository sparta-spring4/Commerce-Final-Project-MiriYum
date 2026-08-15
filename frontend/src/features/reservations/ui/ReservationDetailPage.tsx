import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ROUTES } from '../../../app/routes'
import { hasErrorCode } from '../../../shared/api/apiError'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Icon } from '../../../shared/ui/Icon'
import { formatPrice } from '../../store-search/model/labels'
import {
  RESERVATION_STATUS_LABEL,
  RESERVATION_STATUS_TONE,
  formatReservationTime,
} from '../../consumer-account/model/reservationDisplay'
import { useCancelReservation, useReservation } from '../api/queries'
import { ReservationErrorCode, toCancelMessage } from '../model/errors'

/**
 * 예약 상세와 취소.
 *
 * 본인 예약이 아니거나 없는 예약은 서버가 모두 404로 답한다. 화면도 두 경우를
 * 나누어 표시하지 않는다. 나누면 남의 예약 식별자 존재를 확인하는 통로가 된다.
 */
export function ReservationDetailPage() {
  const { reservationId = '' } = useParams()
  const detail = useReservation(reservationId)

  if (detail.isPending) {
    return (
      <div className="mi-container mi-container--narrow reservation-detail">
        <Loading label="예약 정보를 불러오는 중입니다." />
      </div>
    )
  }

  if (detail.isError) {
    const notFound = hasErrorCode(detail.error, ReservationErrorCode.NOT_FOUND)
    return (
      <div className="mi-container mi-container--narrow reservation-detail">
        <ErrorState
          error={detail.error}
          message={notFound ? '예약을 찾을 수 없습니다.' : undefined}
          onRetry={notFound ? undefined : () => void detail.refetch()}
        />
        <p>
          <Link to={ROUTES.myReservations}>내 예약으로 돌아가기</Link>
        </p>
      </div>
    )
  }

  const reservation = detail.data

  return (
    <div className="mi-container mi-container--narrow reservation-detail">
      <nav aria-label="이동 경로" className="reservation-detail__breadcrumb">
        <Link to={ROUTES.myReservations}>
          <Icon name="arrowLeft" className="mi-icon--sm" />내 예약
        </Link>
      </nav>

      <header className="reservation-detail__header">
        <Badge tone={RESERVATION_STATUS_TONE[reservation.status]}>
          {RESERVATION_STATUS_LABEL[reservation.status]}
        </Badge>
        <h1>{reservation.storeName}</h1>
        <p className="reservation-detail__meta">
          <Icon name="calendar" />
          {formatReservationTime(reservation)}
        </p>
      </header>

      <section
        className="mi-card mi-card--roomy reservation-detail__section"
        aria-label="예약 내용"
      >
        <div className="mi-card__body mi-card__body--roomy">
          {/* 시안 `_10`의 아이콘 + 레이블 + 값 묶음. */}
          <div className="reservation-detail__grid">
            <div className="reservation-detail__item">
              <Icon name="group" />
              <div>
                <p className="reservation-detail__label">인원</p>
                <p className="reservation-detail__value">
                  {`성인 ${reservation.party.adultCount}명 · 아동 ${reservation.party.childCount}명 · 영유아 ${reservation.party.infantCount}명 (총 ${reservation.party.totalCount}명)`}
                </p>
              </div>
            </div>

            <div className="reservation-detail__item">
              <Icon name="store" />
              <div>
                <p className="reservation-detail__label">매장</p>
                <p className="reservation-detail__value">
                  <Link to={`/stores/${reservation.storeId}`}>매장 정보 보기</Link>
                </p>
              </div>
            </div>
          </div>

          <div className="reservation-detail__menus">
            <p className="reservation-detail__menus-head">
              <Icon name="menu" />
              미리 선택한 메뉴
            </p>
            {reservation.menuSelections.length === 0 ? (
              <p className="reservation-detail__value">선택한 메뉴가 없습니다.</p>
            ) : (
              <ul className="reservation-detail__menu-list">
                {reservation.menuSelections.map((item) => (
                  <li key={item.menuId}>
                    <span>{`${item.menuName} x ${item.quantity}`}</span>
                    <span className="reservation-detail__amount">
                      {formatPrice(item.unitPrice * item.quantity)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {reservation.status === 'CANCELLED' && (
            <dl className="reservation-detail__list">
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
        </div>
      </section>

      {reservation.status === 'CONFIRMED' && (
        <CancelSection reservationId={reservationId} />
      )}
    </div>
  )
}

function CancelSection({ reservationId }: { reservationId: string }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [idempotencyKey, setIdempotencyKey] = useState(createIdempotencyKey)

  const mutation = useCancelReservation(reservationId)

  function handleCancel() {
    setError(null)
    mutation.mutate(
      { reason: reason.trim() || undefined, idempotencyKey },
      {
        onSuccess: () => setOpen(false),
        onError: (cause) => {
          setError(toCancelMessage(cause))
          // 확정 실패 뒤 다음 제출은 새 시도다.
          setIdempotencyKey(createIdempotencyKey())
        },
      },
    )
  }

  if (!open) {
    return (
      <section className="reservation-detail__section">
        <Button variant="danger" onClick={() => setOpen(true)}>
          예약 취소하기
        </Button>
      </section>
    )
  }

  return (
    <section className="mi-card reservation-detail__section" aria-label="예약 취소">
      <div className="mi-card__body">
        <h2>예약을 취소할까요?</h2>
        {error !== null && <Alert tone="error" title={error} />}

        <label className="mi-field">
          <span className="mi-field__label">취소 사유 (선택)</span>
          <textarea
            className="mi-field__control reservation-detail__reason"
            name="reason"
            maxLength={500}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>

        <div className="reservation-detail__actions">
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
