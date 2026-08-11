import { Link, useSearchParams } from 'react-router'
import { Badge } from '../../../shared/ui/Badge'
import { EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Pagination } from '../../../shared/ui/Pagination'
import { useMyReservations } from '../api/queries'
import {
  DEFAULT_RESERVATION_SORT,
  RESERVATION_STATUSES,
  RESERVATION_STATUS_LABEL,
  RESERVATION_STATUS_TONE,
  RESERVATION_SORTS,
  formatPartySize,
  formatReservationTime,
  type ReservationHistoryStatus,
  type ReservationSort,
} from '../model/reservationDisplay'

const STATUS_SET = new Set<string>(RESERVATION_STATUSES)
const SORT_SET = new Set<string>(RESERVATION_SORTS)

/**
 * 내 예약 내역.
 *
 * 상태 필터와 페이지는 URL에 둔다. 목록에서 상세로 갔다 돌아와도 조건이 유지된다.
 */
export function MyReservationsPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const statusParam = searchParams.get('status')
  const status =
    statusParam !== null && STATUS_SET.has(statusParam)
      ? (statusParam as ReservationHistoryStatus)
      : undefined

  const sortParam = searchParams.get('sort')
  const sort =
    sortParam !== null && SORT_SET.has(sortParam)
      ? (sortParam as ReservationSort)
      : DEFAULT_RESERVATION_SORT

  const pageParam = Number.parseInt(searchParams.get('page') ?? '', 10)
  const page = Number.isInteger(pageParam) && pageParam > 0 ? pageParam : 0

  const reservations = useMyReservations({ status, page, sort })

  function update(next: {
    status?: ReservationHistoryStatus
    sort?: ReservationSort
    page?: number
  }) {
    const params = new URLSearchParams()
    const nextStatus = 'status' in next ? next.status : status
    const nextSort = next.sort ?? sort
    // 필터가 바뀌면 첫 페이지부터 다시 본다.
    const nextPage = next.page ?? 0

    if (nextStatus !== undefined) {
      params.set('status', nextStatus)
    }
    if (nextSort !== DEFAULT_RESERVATION_SORT) {
      params.set('sort', nextSort)
    }
    if (nextPage > 0) {
      params.set('page', String(nextPage))
    }
    setSearchParams(params)
  }

  return (
    <div className="mi-container mypage">
      <header className="mypage__header">
        <h1>내 예약 관리</h1>
        <p>다가오는 미식 경험과 지난 방문 기록을 확인하세요.</p>
      </header>

      <div className="reservation-filters" role="group" aria-label="상태 필터">
        <button
          type="button"
          className="mi-chip"
          aria-pressed={status === undefined}
          onClick={() => update({ status: undefined })}
        >
          전체
        </button>
        {RESERVATION_STATUSES.map((value) => (
          <button
            key={value}
            type="button"
            className="mi-chip"
            aria-pressed={status === value}
            onClick={() => update({ status: value })}
          >
            {RESERVATION_STATUS_LABEL[value]}
          </button>
        ))}
      </div>

      {reservations.isPending && <Loading label="예약 내역을 불러오는 중입니다." />}

      {reservations.isError && (
        <ErrorState
          error={reservations.error}
          onRetry={() => void reservations.refetch()}
        />
      )}

      {reservations.isSuccess && (
        <ReservationList
          data={reservations.data}
          onPageChange={(next) => update({ page: next })}
        />
      )}
    </div>
  )
}

function ReservationList({
  data,
  onPageChange,
}: {
  data: NonNullable<ReturnType<typeof useMyReservations>['data']>
  onPageChange: (page: number) => void
}) {
  // 빈 배열은 오류가 아니라 정상 empty result다.
  if (data.items.length === 0) {
    return (
      <EmptyState
        title="예약 내역이 없습니다."
        description="매장을 찾아 첫 예약을 만들어 보세요."
      />
    )
  }

  return (
    <>
      <ul className="reservation-list">
        {data.items.map((item) => (
          <li key={item.reservationId} className="mi-card reservation-list__item">
            <div className="mi-card__body">
              <Badge tone={RESERVATION_STATUS_TONE[item.status]}>
                {RESERVATION_STATUS_LABEL[item.status]}
              </Badge>

              <h2 className="reservation-list__store">
                <Link to={`/reservations/${item.reservationId}`}>
                  {item.storeName}
                </Link>
              </h2>

              <p className="reservation-list__meta">
                {formatReservationTime(item)}
              </p>
              <p className="reservation-list__meta">
                {formatPartySize(item.partySize)}
              </p>
            </div>
          </li>
        ))}
      </ul>

      <Pagination
        number={data.page.number}
        totalPages={data.page.totalPages}
        totalElements={data.page.totalElements}
        hasNext={data.page.hasNext}
        onChange={onPageChange}
      />
    </>
  )
}
