import type { BadgeTone } from '../../../shared/ui/Badge'
import type { components } from '../../../shared/api/generated/reservation'

export type ReservationHistoryItem =
  components['schemas']['ReservationHistoryItem']
export type ReservationHistoryStatus =
  components['schemas']['ReservationHistoryStatus']
export type ReservationDetail = components['schemas']['ReservationDetail']

/** 계약이 정한 예약 내역 상태. 결제·노쇼·체크인 상태를 섞지 않는다. */
export const RESERVATION_STATUSES: readonly ReservationHistoryStatus[] = [
  'CONFIRMED',
  'CANCELLED',
  'FULFILLED',
]

export const RESERVATION_STATUS_LABEL: Record<ReservationHistoryStatus, string> =
  {
    CONFIRMED: '예약 확정',
    CANCELLED: '취소됨',
    FULFILLED: '방문 완료',
  }

export const RESERVATION_STATUS_TONE: Record<
  ReservationHistoryStatus,
  BadgeTone
> = {
  CONFIRMED: 'positive',
  CANCELLED: 'neutral',
  FULFILLED: 'attention',
}

/** 계약이 정한 정렬. 그 밖의 값은 서버가 400을 반환한다. */
export const RESERVATION_SORTS = [
  'createdAt,desc',
  'createdAt,asc',
  'serviceDate,desc',
  'serviceDate,asc',
] as const

export type ReservationSort = (typeof RESERVATION_SORTS)[number]

export const DEFAULT_RESERVATION_SORT: ReservationSort = 'createdAt,desc'

/**
 * 예약 시각 표시.
 *
 * `timeStatus`가 `LEGACY_UNRESOLVED`이면 서버가 startAt·serviceEndAt·timeZoneId를
 * null로 준다. 이때 현지 시각을 임의로 조합해 보여 주면 없는 정보를 지어내는 것이다.
 * 날짜만 표시하고 시각은 확인 불가로 남긴다.
 */
export function formatReservationTime(reservation: {
  timeStatus: ReservationHistoryItem['timeStatus']
  serviceDate: string
  startAt: string | null
  timeZoneId: string | null
}): string {
  if (reservation.timeStatus !== 'RESOLVED' || reservation.startAt === null) {
    return `${reservation.serviceDate} (시각 정보 없음)`
  }

  const parsed = new Date(reservation.startAt)
  if (Number.isNaN(parsed.getTime())) {
    return `${reservation.serviceDate} (시각 정보 없음)`
  }

  // 매장 시간대 기준으로 읽는다. 사용자의 기기 시간대로 바꿔 표시하면
  // 매장이 안내한 시각과 달라진다.
  //
  // 24시간 표기를 쓴다. 예약 시각에서 오전·오후를 잘못 읽으면 방문 자체가
  // 어긋나고, 런타임 ICU 데이터에 따라 오전·오후 표기가 영문으로 나오기도 한다.
  const formatter = new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    hour12: false,
    timeZone: reservation.timeZoneId ?? 'Asia/Seoul',
  })
  return formatter.format(parsed)
}

export function formatPartySize(totalCount: number): string {
  return `${totalCount}명`
}
