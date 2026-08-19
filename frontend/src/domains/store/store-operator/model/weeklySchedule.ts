import {
  DAY_LABEL,
  DAY_OF_WEEK,
  type DailySchedule,
  type DailyTimeSlots,
  type DayOfWeek,
  type TimeRange,
} from './types'

/**
 * 주간 스케줄 편집 모델.
 *
 * 영업시간과 예약 접수 시간대 계약은 둘 다 **월~일 전체 초안**을 만든다.
 * 일부 요일만 보내 서버 값과 로컬 병합을 시도하지 않는다. 따라서 편집 모델도
 * 항상 7일을 모두 들고 있고, 휴무 요일은 빈 배열로 제출한다.
 *
 * 계약의 시각 형식은 `HH:mm`이고 구간은 `[startTime, endTime)`이다.
 */

/** `StoreScheduleLocalTime` pattern과 같다. */
const TIME_PATTERN = /^([01][0-9]|2[0-3]):[0-5][0-9]$/

/** `DailySchedule.businessHours`·`breakTimes`·`DailyTimeSlots.slots`의 maxItems */
export const MAX_RANGES_PER_DAY = 48

export interface DayScheduleDraft {
  dayOfWeek: DayOfWeek
  /** false면 그 요일은 빈 배열로 제출한다. 입력값은 화면에 남겨 둔다. */
  open: boolean
  businessHours: TimeRange[]
  breakTimes: TimeRange[]
}

export interface DayTimeSlotsDraft {
  dayOfWeek: DayOfWeek
  open: boolean
  slots: TimeRange[]
}

export function emptyRange(): TimeRange {
  return { startTime: '', endTime: '' }
}

export function createScheduleDraft(): DayScheduleDraft[] {
  return DAY_OF_WEEK.map((dayOfWeek) => ({
    dayOfWeek,
    open: false,
    businessHours: [],
    breakTimes: [],
  }))
}

export function createTimeSlotsDraft(): DayTimeSlotsDraft[] {
  return DAY_OF_WEEK.map((dayOfWeek) => ({
    dayOfWeek,
    open: false,
    slots: [],
  }))
}

/** 분 단위로 바꿔 비교한다. 형식이 어긋나면 null이다. */
export function toMinutes(time: string): number | null {
  if (!TIME_PATTERN.test(time)) {
    return null
  }
  const [hour, minute] = time.split(':')
  return Number(hour) * 60 + Number(minute)
}

function overlaps(left: TimeRange, right: TimeRange): boolean {
  const leftStart = toMinutes(left.startTime)
  const leftEnd = toMinutes(left.endTime)
  const rightStart = toMinutes(right.startTime)
  const rightEnd = toMinutes(right.endTime)
  if (
    leftStart === null ||
    leftEnd === null ||
    rightStart === null ||
    rightEnd === null
  ) {
    return false
  }
  // 반열린 구간이므로 끝과 시작이 같은 건 겹치는 것이 아니다.
  return leftStart < rightEnd && rightStart < leftEnd
}

function isWithinAny(range: TimeRange, containers: TimeRange[]): boolean {
  const start = toMinutes(range.startTime)
  const end = toMinutes(range.endTime)
  if (start === null || end === null) {
    return false
  }
  return containers.some((container) => {
    const containerStart = toMinutes(container.startTime)
    const containerEnd = toMinutes(container.endTime)
    if (containerStart === null || containerEnd === null) {
      return false
    }
    return containerStart <= start && end <= containerEnd
  })
}

/** 필드 오류 키. 화면이 같은 규칙으로 입력 옆에 붙인다. */
export function rangeErrorKey(
  dayOfWeek: DayOfWeek,
  section: 'business' | 'break' | 'slot',
  index: number,
): string {
  return `${dayOfWeek}.${section}.${index}`
}

function validateRangeList(
  errors: Record<string, string>,
  dayOfWeek: DayOfWeek,
  section: 'business' | 'break' | 'slot',
  ranges: TimeRange[],
): void {
  ranges.forEach((range, index) => {
    const key = rangeErrorKey(dayOfWeek, section, index)
    const start = toMinutes(range.startTime)
    const end = toMinutes(range.endTime)

    if (start === null || end === null) {
      errors[key] = '시각을 HH:MM 형식으로 입력해 주세요.'
      return
    }
    if (start >= end) {
      errors[key] = '시작 시각이 종료 시각보다 앞서야 합니다.'
      return
    }
    const conflict = ranges.some(
      (other, otherIndex) => otherIndex !== index && overlaps(range, other),
    )
    if (conflict) {
      errors[key] = '같은 요일의 다른 구간과 겹칩니다.'
    }
  })
}

/**
 * 영업시간 초안 검증.
 *
 * 브레이크타임은 영업 구간 안에 들어가야 한다. 서버도 `STORE_006`으로 같은
 * 판정을 하며, 여기 검증은 제출 전에 어느 구간이 문제인지 보여 주기 위한 것이다.
 */
export function validateScheduleDraft(
  week: readonly DayScheduleDraft[],
): Readonly<Record<string, string>> {
  const errors: Record<string, string> = {}

  for (const day of week) {
    if (!day.open) {
      continue
    }
    if (day.businessHours.length === 0) {
      errors[day.dayOfWeek] =
        '영업일로 두려면 영업 구간을 최소 한 개 입력해 주세요.'
      continue
    }
    validateRangeList(errors, day.dayOfWeek, 'business', day.businessHours)
    validateRangeList(errors, day.dayOfWeek, 'break', day.breakTimes)

    day.breakTimes.forEach((breakTime, index) => {
      const key = rangeErrorKey(day.dayOfWeek, 'break', index)
      if (errors[key] !== undefined) {
        return
      }
      if (!isWithinAny(breakTime, day.businessHours)) {
        errors[key] = '휴게시간은 영업 구간 안에 있어야 합니다.'
      }
    })
  }

  return errors
}

export function validateTimeSlotsDraft(
  week: readonly DayTimeSlotsDraft[],
): Readonly<Record<string, string>> {
  const errors: Record<string, string> = {}

  for (const day of week) {
    if (!day.open) {
      continue
    }
    if (day.slots.length === 0) {
      errors[day.dayOfWeek] =
        '접수하는 요일로 두려면 시간대를 최소 한 개 입력해 주세요.'
      continue
    }
    validateRangeList(errors, day.dayOfWeek, 'slot', day.slots)
  }

  return errors
}

/**
 * 게시된 영업시간과 비교해 접수 시간대 충돌을 찾는다.
 *
 * 운영자용 영업시간 조회 계약이 없어 기준값은 **게시된 공개 상세**다. 그래서
 * 결과는 제출을 막는 오류가 아니라 경고다. 최종 판정은 서버가 한다.
 */
export function findTimeSlotConflicts(
  week: readonly DayTimeSlotsDraft[],
  publishedHours: readonly DailySchedule[],
): Readonly<Record<string, string>> {
  const warnings: Record<string, string> = {}
  const byDay = new Map(publishedHours.map((day) => [day.dayOfWeek, day]))

  for (const day of week) {
    if (!day.open) {
      continue
    }
    const published = byDay.get(day.dayOfWeek)
    if (published === undefined || published.businessHours.length === 0) {
      warnings[day.dayOfWeek] =
        `게시된 영업시간에 ${DAY_LABEL[day.dayOfWeek]}이 없습니다.`
      continue
    }
    day.slots.forEach((slot, index) => {
      const key = rangeErrorKey(day.dayOfWeek, 'slot', index)
      if (!isWithinAny(slot, published.businessHours)) {
        warnings[key] = '게시된 영업시간 밖입니다.'
        return
      }
      if (published.breakTimes.some((breakTime) => overlaps(slot, breakTime))) {
        warnings[key] = '게시된 휴게시간과 겹칩니다.'
      }
    })
  }

  return warnings
}

/** 초안을 계약 본문으로 옮긴다. 휴무 요일도 요일 자체는 반드시 포함한다. */
export function toWeeklyOperatingHours(
  week: readonly DayScheduleDraft[],
): DailySchedule[] {
  return week.map((day) => ({
    dayOfWeek: day.dayOfWeek,
    businessHours: day.open ? day.businessHours : [],
    breakTimes: day.open ? day.breakTimes : [],
  }))
}

export function toWeeklyTimeSlots(
  week: readonly DayTimeSlotsDraft[],
): DailyTimeSlots[] {
  return week.map((day) => ({
    dayOfWeek: day.dayOfWeek,
    slots: day.open ? day.slots : [],
  }))
}

/** 불변 갱신 도우미. 배열을 제자리에서 바꾸지 않는다. */
export function replaceAt<T>(items: readonly T[], index: number, next: T): T[] {
  return items.map((item, position) => (position === index ? next : item))
}

export function removeAt<T>(items: readonly T[], index: number): T[] {
  return items.filter((_, position) => position !== index)
}
