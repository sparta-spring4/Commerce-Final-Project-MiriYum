import { PUBLIC_PATHS } from '../../../../app/routes/paths/publicPaths'
import { Link, useParams } from 'react-router'
import { hasErrorCode } from '../../../../shared/api/apiError'
import { CompletionRow, CompletionScreen } from '../../../../shared/ui/Completion'
import { ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { formatPrice } from '../../../store/public/model/labels'
import { usePickupReservation } from '../api/queries'
import { PickupErrorCode, pickupTotalPrice } from '../model/pickup'

/**
 * 픽업 예약 완료.
 *
 * 예약 완료 화면과 같은 형태를 쓴다. 일반 사용자 픽업 목록 계약이 없으므로
 * "예약 내역 보기" 대신 픽업 상세로 보낸다.
 */
export function PickupCompletePage() {
  const { pickupReservationId = '' } = useParams()
  const detail = usePickupReservation(pickupReservationId)

  if (detail.isPending) {
    return (
      <div className="mi-container">
        <Loading label="픽업 예약을 불러오는 중입니다." />
      </div>
    )
  }

  if (detail.isError) {
    const notFound = hasErrorCode(detail.error, PickupErrorCode.NOT_FOUND)
    return (
      <div className="mi-container mi-container--narrow">
        <ErrorState
          error={detail.error}
          message={notFound ? '픽업 예약을 찾을 수 없습니다.' : undefined}
          onRetry={notFound ? undefined : () => void detail.refetch()}
        />
        <p>
          <Link to={PUBLIC_PATHS.stores}>매장 찾기로 돌아가기</Link>
        </p>
      </div>
    )
  }

  const reservation = detail.data

  // 확정이 아닌 픽업 예약을 완료로 표시하지 않는다.
  if (reservation.status !== 'CONFIRMED') {
    return (
      <div className="mi-container mi-container--narrow">
        <ErrorState
          error={null}
          message="이 픽업 예약은 확정 상태가 아닙니다. 상세에서 현재 상태를 확인해 주세요."
        />
        <p>
          <Link to={`/pickup-reservations/${reservation.pickupReservationId}`}>
            픽업 예약 상세 보기
          </Link>
        </p>
      </div>
    )
  }

  return (
    <CompletionScreen
      title="픽업 예약이 완료되었습니다!"
      description="선택한 시간에 맞춰 준비해 두겠습니다. 도착하시면 바로 받아 가실 수 있어요."
      referenceLabel="픽업 예약 번호"
      referenceValue={reservation.pickupReservationId}
      actions={
        <>
          <Link className="mi-button mi-button--ghost" to={PUBLIC_PATHS.home}>
            홈으로 이동
          </Link>
          <Link
            className="mi-button mi-button--primary"
            to={`/pickup-reservations/${reservation.pickupReservationId}`}
          >
            픽업 예약 보기
          </Link>
        </>
      }
    >
      <CompletionRow label="매장">{reservation.storeName}</CompletionRow>
      <CompletionRow label="픽업 시각">
        {`${reservation.pickupDate} ${reservation.pickupTime}`}
      </CompletionRow>
      <CompletionRow label="주문 메뉴">
        {reservation.items
          .map((item) => `${item.menuName} x ${item.quantity}`)
          .join(', ')}
      </CompletionRow>
      <CompletionRow label="합계">
        {formatPrice(pickupTotalPrice(reservation))}
      </CompletionRow>
    </CompletionScreen>
  )
}
