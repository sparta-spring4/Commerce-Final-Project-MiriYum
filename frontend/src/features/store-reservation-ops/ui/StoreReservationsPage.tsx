import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ROUTES, fillPath } from '../../../app/routes'
import { Badge } from '../../../shared/ui/Badge'
import { SelectField, TextField } from '../../../shared/ui/Field'
import { EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Pagination } from '../../../shared/ui/Pagination'
import { PageHeader, useAdoptStoreFromRoute } from '../../store-operator'
import { useStoreReservations, type StoreReservationQuery } from '../api/queries'
import { reservationOpsErrorMessage } from '../model/errors'
import {
  RESERVATION_SORT_LABEL,
  RESERVATION_STATUSES,
  RESERVATION_STATUS_LABEL,
  type ReservationSort,
  type ReservationStatus,
  type ReservationSummary,
} from '../model/types'

const PAGE_SIZE = 20

const SORTS: readonly ReservationSort[] = [
  'serviceDate,asc',
  'serviceDate,desc',
  'createdAt,asc',
  'createdAt,desc',
]

/**
 * 매장 예약 목록.
 *
 * 취소·방문 완료는 되돌릴 수 없고 취소는 사유까지 요구한다. 목록 행에서 한 번의
 * 클릭으로 실행하지 않고 상세에서 처리한다. 대신 처리할 수 있는 예약은 행에서
 * 바로 알아볼 수 있게 링크 문구를 "처리"로 둔다.
 *
 * 계약의 목록 항목에는 고객 이름·연락처가 없다. 시안에 있더라도 없는 필드를
 * 만들어 표시하지 않는다.
 */
export function StoreReservationsPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)

  const [serviceDate, setServiceDate] = useState('')
  const [status, setStatus] = useState<ReservationStatus | ''>('')
  const [sort, setSort] = useState<ReservationSort>('serviceDate,asc')
  const [page, setPage] = useState(0)

  // 빈 값은 "필터 없음"이다. 빈 문자열을 그대로 보내면 서버가 400을 준다.
  const query: StoreReservationQuery = {
    page,
    size: PAGE_SIZE,
    sort,
  }
  if (serviceDate.length > 0) {
    query.serviceDate = serviceDate
  }
  if (status !== '') {
    query.status = status
  }
  const reservations = useStoreReservations(storeId, query)

  return (
    <>
      <PageHeader
        title="예약 목록"
        description="접수된 예약을 날짜·상태로 조회합니다."
      />

      <section className="mi-card">
        <div className="mi-card__body">
          {/*
            시안의 도구 막대. 날짜·상태·정렬을 한 줄에 두고 좁아지면 접힌다.

            시안은 상태를 건수가 붙은 알약 버튼으로 고르지만, 목록 응답에 상태별
            집계가 없어 건수를 지어내야 한다. 그래서 상태는 선택 상자 하나로
            둔다. 같은 상태를 고르는 컨트롤을 둘로 늘리지도 않는다.
          */}
          <div className="op-toolbar">
            <div className="op-toolbar__search">
              <TextField
                label="이용 날짜"
                type="date"
                value={serviceDate}
                help="비우면 전체 날짜를 조회합니다."
                onChange={(event) => {
                  setServiceDate(event.target.value)
                  setPage(0)
                }}
              />
            </div>
            <div className="op-toolbar__filters">
              <div className="op-toolbar__field">
                <SelectField
                  label="예약 상태"
                  value={status}
                  onChange={(event) => {
                    setStatus(event.target.value as ReservationStatus | '')
                    setPage(0)
                  }}
                >
                  <option value="">전체</option>
                  {RESERVATION_STATUSES.map((value) => (
                    <option key={value} value={value}>
                      {RESERVATION_STATUS_LABEL[value]}
                    </option>
                  ))}
                </SelectField>
              </div>
              <div className="op-toolbar__field">
                <SelectField
                  label="정렬"
                  value={sort}
                  onChange={(event) => {
                    setSort(event.target.value as ReservationSort)
                    setPage(0)
                  }}
                >
                  {SORTS.map((value) => (
                    <option key={value} value={value}>
                      {RESERVATION_SORT_LABEL[value]}
                    </option>
                  ))}
                </SelectField>
              </div>
            </div>
          </div>

          {reservations.isPending && (
            <Loading label="예약 목록을 불러오는 중입니다." />
          )}

          {reservations.isError && (
            <ErrorState
              error={reservations.error}
              message={reservationOpsErrorMessage(reservations.error)}
              onRetry={() => void reservations.refetch()}
            />
          )}

          {reservations.isSuccess && (
            <>
              {reservations.data.items.length === 0 ? (
                <EmptyState
                  title="조건에 맞는 예약이 없습니다."
                  description="날짜나 상태 조건을 바꿔 다시 조회해 보세요."
                />
              ) : (
                <ReservationTable
                  storeId={storeId}
                  items={reservations.data.items}
                />
              )}
              <Pagination
                number={reservations.data.page.number}
                totalPages={reservations.data.page.totalPages}
                totalElements={reservations.data.page.totalElements}
                hasNext={reservations.data.page.hasNext}
                unitLabel="건"
                onChange={setPage}
              />
            </>
          )}
        </div>
      </section>
    </>
  )
}

function ReservationTable({
  storeId,
  items,
}: {
  storeId: string
  items: readonly ReservationSummary[]
}) {
  return (
    <div className="op-table-scroll">
      <table className="op-table">
        <caption className="visually-hidden">
          예약 목록. 이용 날짜와 시각, 인원, 상태를 표시합니다.
        </caption>
        <thead>
          <tr>
            <th scope="col">예약 번호</th>
            <th scope="col">이용 날짜</th>
            <th scope="col">시각</th>
            <th scope="col" className="op-table__numeric">
              인원
            </th>
            <th scope="col">상태</th>
            <th scope="col">관리</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.reservationId}>
              <th scope="row">{item.reservationId}</th>
              <td>{item.serviceDate}</td>
              <td>{describeStartTime(item)}</td>
              <td className="op-table__numeric">{`${item.totalPartySize}명`}</td>
              <td>
                <Badge tone={statusTone(item.status)}>
                  {RESERVATION_STATUS_LABEL[item.status]}
                </Badge>
              </td>
              <td>
                <Link
                  className="mi-button mi-button--ghost mi-button--sm"
                  to={fillPath(ROUTES.storeOperatorReservation, {
                    storeId,
                    reservationId: item.reservationId,
                  })}
                  aria-label={`예약 ${item.reservationId} ${item.status === 'CONFIRMED' ? '처리' : '상세'}`}
                >
                  {item.status === 'CONFIRMED' ? '처리' : '상세'}
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * 시각 표시.
 *
 * `LEGACY_UNRESOLVED`는 서버가 시각 스냅샷을 확정하지 못한 행이다. 임의로
 * 변환해 그럴듯한 시각을 만들지 않고 확정되지 않았다고 밝힌다.
 */
function describeStartTime(item: ReservationSummary): string {
  if (item.timeStatus === 'LEGACY_UNRESOLVED' || item.startAt === null) {
    return '시각 미확정'
  }
  return formatInStoreZone(item.startAt, item.timeZoneId)
}

function formatInStoreZone(isoDateTime: string, timeZoneId: string | null): string {
  const instant = new Date(isoDateTime)
  if (Number.isNaN(instant.getTime())) {
    return isoDateTime
  }
  // 24시간 표기를 강제한다. 일부 Node ICU 구성이 ko-KR 오전·오후를 "PM"으로 낸다.
  const formatter = new Intl.DateTimeFormat('ko-KR', {
    ...(timeZoneId === null ? {} : { timeZone: timeZoneId }),
    hourCycle: 'h23',
    hour: '2-digit',
    minute: '2-digit',
  })
  return formatter.format(instant)
}

function statusTone(
  status: ReservationStatus,
): 'positive' | 'negative' | 'neutral' {
  if (status === 'CONFIRMED') {
    return 'positive'
  }
  return status === 'CANCELLED' ? 'negative' : 'neutral'
}
