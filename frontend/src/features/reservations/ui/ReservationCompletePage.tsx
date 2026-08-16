import { Link, useParams } from 'react-router'
import { ROUTES } from '../../../app/routes'
import { hasErrorCode } from '../../../shared/api/apiError'
import {
  CompletionRow,
  CompletionScreen,
} from '../../../shared/ui/Completion'
import { ErrorState, Loading } from '../../../shared/ui/Feedback'
import { formatReservationTime } from '../../consumer-account/model/reservationDisplay'
import { useReservation } from '../api/queries'
import { ReservationErrorCode } from '../model/errors'

/**
 * 예약 완료.
 *
 * 생성 성공 직후 도착하는 화면이다. 생성 mutation이 상세를 캐시에 미리 넣으므로
 * 보통 추가 요청 없이 즉시 그려지고, 새로고침하면 상세를 다시 읽는다.
 *
 * 완료 화면은 서버가 확정한 예약만 보여 준다. 상태가 `CONFIRMED`가 아니면
 * 축하 문구 대신 상태를 다시 확인하도록 안내한다.
 */
export function ReservationCompletePage() {
  const { reservationId = '' } = useParams()
  const detail = useReservation(reservationId)

  if (detail.isPending) {
    return (
      <div className="mi-container">
        <Loading label="예약 정보를 불러오는 중입니다." />
      </div>
    )
  }

  if (detail.isError) {
    const notFound = hasErrorCode(detail.error, ReservationErrorCode.NOT_FOUND)
    return (
      <div className="mi-container mi-container--narrow">
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

  // 확정이 아닌 예약을 완료로 표시하지 않는다.
  if (reservation.status !== 'CONFIRMED') {
    return (
      <div className="mi-container mi-container--narrow">
        <ErrorState
          error={null}
          message="이 예약은 확정 상태가 아닙니다. 예약 상세에서 현재 상태를 확인해 주세요."
        />
        <p>
          <Link to={`/reservations/${reservation.reservationId}`}>
            예약 상세 보기
          </Link>
        </p>
      </div>
    )
  }

  const { party } = reservation

  return (
    <CompletionScreen
      title="예약이 완료되었습니다!"
      description="설레는 마음으로 기다리겠습니다. 즐거운 다이닝 경험을 위해 정성껏 준비할게요."
      referenceLabel="예약 번호"
      referenceValue={reservation.reservationId}
      actions={
        <>
          <Link className="mi-button mi-button--ghost" to={ROUTES.home}>
            홈으로 이동
          </Link>
          <Link className="mi-button mi-button--primary" to={ROUTES.myReservations}>
            예약 내역 보기
          </Link>
        </>
      }
    >
      <CompletionRow label="매장">{reservation.storeName}</CompletionRow>
      <CompletionRow label="일시">
        {formatReservationTime(reservation)}
      </CompletionRow>
      <CompletionRow label="인원">
        {`성인 ${party.adultCount}명 · 아동 ${party.childCount}명 · 영유아 ${party.infantCount}명`}
      </CompletionRow>
      {reservation.menuSelections.length > 0 && (
        <CompletionRow label="미리 선택한 메뉴">
          {reservation.menuSelections
            .map((item) => `${item.menuName} x ${item.quantity}`)
            .join(', ')}
        </CompletionRow>
      )}
    </CompletionScreen>
  )
}
