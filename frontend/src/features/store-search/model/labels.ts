import type { BadgeTone } from '../../../shared/ui/Badge'
import type {
  DailySchedule,
  OperationStatus,
  PublicMenu,
  Region,
  ReservationAvailability,
} from './searchParams'

/**
 * enum 표시명.
 *
 * catalog code(카테고리·태그)는 서버가 `displayName`을 주므로 여기서 만들지 않는다.
 * 여기 있는 값은 OpenAPI가 고정한 enum, 즉 서버가 표시명을 주지 않는 것뿐이다.
 */

export const REGION_LABEL: Record<Region, string> = {
  SEOUL: '서울',
  BUSAN: '부산',
  DAEGU: '대구',
  DAEJEON: '대전',
  GWANGJU: '광주',
}

export const OPERATION_STATUS_LABEL: Record<OperationStatus, string> = {
  OPEN: '영업 중',
  TEMPORARILY_CLOSED: '임시 휴무',
  CLOSED: '폐점',
}

export const OPERATION_STATUS_TONE: Record<OperationStatus, BadgeTone> = {
  OPEN: 'positive',
  TEMPORARILY_CLOSED: 'attention',
  CLOSED: 'negative',
}

/**
 * 가용성 3분기는 서로 다른 의미다.
 *
 * `NOT_REQUESTED`를 `UNAVAILABLE`로 합치면 조건을 넣지 않은 사용자에게
 * 예약이 불가능하다고 잘못 말하게 된다. 세 값을 각각 다른 문구로 표시한다.
 */
export const AVAILABILITY_LABEL: Record<ReservationAvailability, string> = {
  NOT_REQUESTED: '예약 조건 미입력',
  AVAILABLE: '예약 가능',
  UNAVAILABLE: '예약 불가',
}

export const AVAILABILITY_DESCRIPTION: Record<ReservationAvailability, string> =
  {
    NOT_REQUESTED:
      '날짜·시간·인원을 모두 입력하면 예약 가능 여부를 확인할 수 있습니다.',
    AVAILABLE:
      '입력한 조건으로 지금 예약할 수 있습니다. 실제 예약 시점에 다시 확인합니다.',
    UNAVAILABLE: '입력한 조건으로는 예약할 수 없습니다.',
  }

export const AVAILABILITY_TONE: Record<ReservationAvailability, BadgeTone> = {
  NOT_REQUESTED: 'neutral',
  AVAILABLE: 'positive',
  UNAVAILABLE: 'negative',
}

type DayOfWeek = DailySchedule['dayOfWeek']

export const DAY_OF_WEEK_LABEL: Record<DayOfWeek, string> = {
  MONDAY: '월',
  TUESDAY: '화',
  WEDNESDAY: '수',
  THURSDAY: '목',
  FRIDAY: '금',
  SATURDAY: '토',
  SUNDAY: '일',
}

/** 주간 표에서 항상 같은 순서로 보이게 한다. 응답 순서에 의존하지 않는다. */
export const DAY_OF_WEEK_ORDER: readonly DayOfWeek[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
]

export const SALE_STATUS_LABEL: Record<PublicMenu['saleStatus'], string> = {
  SELLING: '판매 중',
  SOLD_OUT: '품절',
  PAUSED: '판매 중지',
}

export const SALE_STATUS_TONE: Record<PublicMenu['saleStatus'], BadgeTone> = {
  SELLING: 'positive',
  SOLD_OUT: 'negative',
  PAUSED: 'neutral',
}

/** 금액은 서버가 정수 원 단위로 준다. 통화 기호를 추측하지 않고 원으로 표시한다. */
export function formatPrice(price: number): string {
  return `${price.toLocaleString('ko-KR')}원`
}

/** catalog code를 표시명으로 옮긴다. 표시명이 없으면 code를 그대로 보여 준다. */
export function catalogLabel(
  code: string,
  displayNames: ReadonlyMap<string, string>,
): string {
  return displayNames.get(code) ?? code
}
