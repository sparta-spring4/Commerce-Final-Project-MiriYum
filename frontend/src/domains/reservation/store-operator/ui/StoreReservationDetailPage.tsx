import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { Link, useParams } from 'react-router'
import { Badge } from '../../../../shared/ui/Badge'
import { ErrorState, Loading } from '../../../../shared/ui/Feedback'
import {
  PageHeader,
  SectionCard,
  SummaryList,
} from '../../../../app/shells/store-operator/OperatorPage'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { useStoreReservation } from '../api/queries'
import { reservationOpsErrorMessage } from '../model/errors'
import { ReservationCommandPanel } from './ReservationCommandPanel'
import { statusTone } from './StoreReservationsPage'
import {
  CANCELLED_BY_LABEL,
  RESERVATION_STATUS_LABEL,
  type ReservationDetail,
} from '../model/types'

/**
 * 매장 예약 상세와 처리.
 *
 * 조회에 이어 취소·방문 완료까지 여기서 끝낸다. 상태별로 실제 성립하는 명령만
 * 열어 둔다. 화면이 서버 규칙을 추측해 버튼을 열면, 눌러도 거절되는 행동을
 * 운영자에게 약속하게 된다.
 */
export function StoreReservationDetailPage() {
  const { storeId = '', reservationId = '' } = useParams<{
    storeId: string
    reservationId: string
  }>()
  useAdoptStoreFromRoute(storeId)

  const query = useStoreReservation(storeId, reservationId)

  return (
    <>
      <PageHeader
        title="예약 상세"
        description="접수된 예약의 확정 내용입니다."
        actions={
          <Link
            className="mi-button mi-button--ghost"
            to={fillPath(STORE_OPERATOR_PATHS.reservations, { storeId })}
          >
            예약 목록
          </Link>
        }
      />

      {query.isPending && <Loading label="예약을 불러오는 중입니다." />}

      {query.isError && (
        <ErrorState
          error={query.error}
          message={reservationOpsErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      )}

      {query.isSuccess && (
        <ReservationFacts storeId={storeId} reservation={query.data} />
      )}
    </>
  )
}

function ReservationFacts({
  storeId,
  reservation,
}: {
  storeId: string
  reservation: ReservationDetail
}) {
  /**
   * `LEGACY_UNRESOLVED`는 서버가 시각 스냅샷을 확정하지 못한 예약이다.
   * 그런 행의 `startAt`은 null이며 화면이 임의로 변환하지 않는다.
   */
  const startAt =
    reservation.timeStatus === 'LEGACY_UNRESOLVED' ? null : reservation.startAt

  return (
    <div className="op-stack">
      <SectionCard title={`예약 ${reservation.reservationId}`}>
        <p className="op-section__hint">
          {/* 색 판정은 목록과 같은 함수를 쓴다. 두 화면이 갈라지면 같은 상태가
              다른 색으로 보인다. */}
          <Badge tone={statusTone(reservation.status)}>
            {RESERVATION_STATUS_LABEL[reservation.status]}
          </Badge>
        </p>

        <SummaryList
          items={[
            { term: '매장', value: reservation.storeName },
            { term: '이용 날짜', value: reservation.serviceDate },
            {
              term: '시작 시각',
              value:
                startAt === null
                  ? '확정되지 않음'
                  : formatDateTime(startAt, reservation.timeZoneId),
            },
            {
              term: '서비스 종료',
              value:
                reservation.serviceEndAt === null
                  ? '확정되지 않음'
                  : formatDateTime(
                      reservation.serviceEndAt,
                      reservation.timeZoneId,
                    ),
            },
            {
              term: '인원',
              value: `성인 ${reservation.party.adultCount} · 아동 ${reservation.party.childCount} · 영유아 ${reservation.party.infantCount} (합계 ${reservation.party.totalCount}명)`,
            },
            {
              term: '접수 시각',
              value: formatDateTime(
                reservation.createdAt,
                reservation.timeZoneId,
              ),
            },
          ]}
        />

        {startAt === null && (
          <p className="op-section__hint">
            서버가 이 예약의 시각 스냅샷을 확정하지 못했습니다. 화면에서 임의로
            변환하지 않습니다.
          </p>
        )}
      </SectionCard>

      <SectionCard title="선택한 메뉴" icon="menu-book">
        {reservation.menuSelections.length === 0 ? (
          <p className="op-section__hint">선택한 메뉴가 없습니다.</p>
        ) : (
          <div className="op-table-scroll">
            <table className="op-table">
              <caption className="visually-hidden">
                예약에 담긴 메뉴와 수량. 예약 시점의 스냅샷입니다.
              </caption>
              <thead>
                <tr>
                  <th scope="col">메뉴</th>
                  <th scope="col" className="op-table__numeric">
                    단가
                  </th>
                  <th scope="col" className="op-table__numeric">
                    수량
                  </th>
                </tr>
              </thead>
              <tbody>
                {reservation.menuSelections.map((item) => (
                  <tr key={item.menuId}>
                    <th scope="row">{item.menuName}</th>
                    <td className="op-table__numeric">
                      {`${item.unitPrice.toLocaleString('ko-KR')}원`}
                    </td>
                    <td className="op-table__numeric">{item.quantity}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      <ReservationCommandPanel storeId={storeId} reservation={reservation} />

      {reservation.status === 'CANCELLED' && (
        <SectionCard title="취소 정보" icon="close">
          <SummaryList
            items={[
              {
                term: '취소 주체',
                value:
                  reservation.cancelledBy === null
                    ? '기록 없음'
                    : CANCELLED_BY_LABEL[reservation.cancelledBy],
              },
              {
                term: '취소 사유',
                value: reservation.cancellationReason ?? '기록 없음',
              },
            ]}
          />
        </SectionCard>
      )}
    </div>
  )
}

function formatDateTime(isoDateTime: string, timeZoneId: string | null): string {
  const instant = new Date(isoDateTime)
  if (Number.isNaN(instant.getTime())) {
    return isoDateTime
  }
  const formatter = new Intl.DateTimeFormat('ko-KR', {
    ...(timeZoneId === null ? {} : { timeZone: timeZoneId }),
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
  const parts = new Map(
    formatter.formatToParts(instant).map((part) => [part.type, part.value]),
  )
  return `${parts.get('year')}-${parts.get('month')}-${parts.get('day')} ${parts.get('hour')}:${parts.get('minute')}`
}
