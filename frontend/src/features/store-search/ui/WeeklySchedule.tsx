import { DAY_OF_WEEK_LABEL, DAY_OF_WEEK_ORDER } from '../model/labels'
import type { DailySchedule, DailyTimeSlots } from '../model/searchParams'

type TimeRange = { startTime: string; endTime: string }

function formatRanges(ranges: TimeRange[], emptyLabel: string): string {
  if (ranges.length === 0) {
    return emptyLabel
  }
  return ranges
    .map((range) => `${range.startTime} – ${range.endTime}`)
    .join(', ')
}

/**
 * 주간 영업시간.
 *
 * 시안에는 "매일 11:30 - 22:00" 한 줄만 있었지만 계약은 요일별 영업시간과
 * 브레이크타임을 준다. 요일별로 다른 매장을 한 줄로 뭉개지 않는다.
 */
export function OperatingHoursTable({ days }: { days: DailySchedule[] }) {
  const byDay = new Map(days.map((day) => [day.dayOfWeek, day]))

  return (
    <table className="store-schedule">
      <caption className="visually-hidden">요일별 영업시간과 브레이크타임</caption>
      <thead>
        <tr>
          <th scope="col">요일</th>
          <th scope="col">영업시간</th>
          <th scope="col">브레이크타임</th>
        </tr>
      </thead>
      <tbody>
        {DAY_OF_WEEK_ORDER.map((dayOfWeek) => {
          const day = byDay.get(dayOfWeek)
          return (
            <tr key={dayOfWeek}>
              <th scope="row">{DAY_OF_WEEK_LABEL[dayOfWeek]}</th>
              <td>{formatRanges(day?.businessHours ?? [], '휴무')}</td>
              <td>{formatRanges(day?.breakTimes ?? [], '없음')}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

/**
 * 주간 예약 접수 시간대.
 *
 * 영업시간과 다르다. 영업 중이어도 예약을 받지 않는 구간이 있을 수 있으므로
 * 두 표를 합치지 않는다.
 */
export function ReservationTimeSlotsTable({ days }: { days: DailyTimeSlots[] }) {
  const byDay = new Map(days.map((day) => [day.dayOfWeek, day]))

  return (
    <table className="store-schedule">
      <caption className="visually-hidden">요일별 예약 접수 시간대</caption>
      <thead>
        <tr>
          <th scope="col">요일</th>
          <th scope="col">예약 접수 시간대</th>
        </tr>
      </thead>
      <tbody>
        {DAY_OF_WEEK_ORDER.map((dayOfWeek) => {
          const day = byDay.get(dayOfWeek)
          return (
            <tr key={dayOfWeek}>
              <th scope="row">{DAY_OF_WEEK_LABEL[dayOfWeek]}</th>
              <td>{formatRanges(day?.slots ?? [], '접수하지 않음')}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
