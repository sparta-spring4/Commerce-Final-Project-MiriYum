import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { hasErrorCode } from '../../../../shared/api/apiError'
import { useIdempotentAttempt } from '../../../../shared/api/useIdempotentAttempt'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Icon } from '../../../../shared/ui/Icon'
import { formatPrice } from '../../../store/public/model/labels'
import {
  RESERVATION_STATUS_LABEL,
  RESERVATION_STATUS_TONE,
  formatReservationTime,
} from '../model/reservationDisplay'
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
          <Link to={CONSUMER_PATHS.myReservations}>내 예약으로 돌아가기</Link>
        </p>
      </div>
    )
  }

  const reservation = detail.data

  return (
    <div className="mi-container mi-container--narrow reservation-detail">
      <nav aria-label="이동 경로" className="reservation-detail__breadcrumb">
        <Link to={CONSUMER_PATHS.myReservations}>
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
        <>
          <section className="reservation-detail__section">
            <Link
              className="mi-button mi-button--primary"
              to={CONSUMER_PATHS.reservationCheckIn.replace(':reservationId', reservationId)}
            >
              체크인 QR 열기
            </Link>
          </section>
          <CancelSection reservationId={reservationId} />
        </>
      )}
    </div>
  )
}

function CancelSection({ reservationId }: { reservationId: string }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  /*
   * 취소 사유는 요청 본문이라 요청 지문의 일부다. 사유가 바뀌면 같은 키를
   * 재사용할 수 없고(`COMMON_007`), 그렇다고 결과 불명 뒤에 새 키를 발급하면
   * 취소가 두 번 나갈 수 있다. 두 규칙을 attempt가 함께 지킨다.
   */
  const normalizedReason = reason.trim()
  const attempt = useIdempotentAttempt(normalizedReason)

  const mutation = useCancelReservation(reservationId)

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
          // 결과 불명이면 사유를 바꾼 재전송을 막는다.
          attempt.settle(cause)
          setError(toCancelMessage(cause))
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
