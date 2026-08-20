import { Link, useSearchParams } from 'react-router'
import { Badge } from '../../../../shared/ui/Badge'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Icon } from '../../../../shared/ui/Icon'
import { Pagination } from '../../../../shared/ui/Pagination'
import { useMyReservations } from '../api/historyQueries'
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
      <header className="mi-page-head">
        <h1 className="mi-page-head__title">내 예약 관리</h1>
        <p className="mi-page-head__lead">
          다가오는 미식 경험과 지난 방문 기록을 확인하세요.
        </p>
      </header>

      <div className="reservation-filters" role="group" aria-label="상태 필터">
        <button
          type="button"
          className="mi-chip mi-chip--tab"
          aria-pressed={status === undefined}
          onClick={() => update({ status: undefined })}
        >
          전체
        </button>
        {RESERVATION_STATUSES.map((value) => (
          <button
            key={value}
            type="button"
            className="mi-chip mi-chip--tab"
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
          <li
            key={item.reservationId}
            className="mi-card mi-card--interactive reservation-list__item"
          >
            <div className="mi-card__body">
              <div className="reservation-list__head">
                <div>
                  <Badge tone={RESERVATION_STATUS_TONE[item.status]}>
                    {RESERVATION_STATUS_LABEL[item.status]}
                  </Badge>
                  <h2 className="reservation-list__store">
                    <Link to={`/reservations/${item.reservationId}`}>
                      {item.storeName}
                    </Link>
                  </h2>
                </div>

                {/* 시안의 날짜 타일. serviceDate는 항상 오므로 늘 그릴 수 있다. */}
                <DateTile serviceDate={item.serviceDate} />
              </div>

              {/*
                목록 항목 안의 사실 나열이다. `ul`로 감싸면 카드 자체가 목록
                항목인데 그 안에 또 목록 항목이 생겨 구조가 두 겹이 된다.
              */}
              <div className="reservation-list__facts">
                <p>
                  <span className="reservation-list__fact-mark" aria-hidden="true">
                    <Icon name="clock" className="mi-icon--sm" />
                  </span>
                  {formatReservationTime(item)}
                </p>
                <p>
                  <span className="reservation-list__fact-mark" aria-hidden="true">
                    <Icon name="group" className="mi-icon--sm" />
                  </span>
                  {formatPartySize(item.partySize)}
                </p>
              </div>

              <div className="reservation-list__foot">
                <Link
                  className="mi-button mi-button--primary mi-button--block"
                  to={`/reservations/${item.reservationId}`}
                >
                  상세 보기
                </Link>
              </div>
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

/**
 * 날짜 타일.
 *
 * `serviceDate`는 계약이 `YYYY-MM-DD`로 고정한다. 파싱해서 월·일만 떼어 낸다.
 * 형식이 어긋나면 타일을 그리지 않는다. 옆의 시각 문구가 날짜를 이미 알린다.
 */
function DateTile({ serviceDate }: { serviceDate: string }) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(serviceDate)
  if (match === null) {
    return null
  }

  return (
    <p className="reservation-list__date" aria-hidden="true">
      <span className="reservation-list__date-month">{`${Number(match[2])}월`}</span>
      <span className="reservation-list__date-day">{Number(match[3])}</span>
    </p>
  )
}
