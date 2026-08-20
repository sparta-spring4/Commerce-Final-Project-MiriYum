import { describe, expect, it } from 'vitest'
import {
  createScheduleDraft,
  createTimeSlotsDraft,
  findTimeSlotConflicts,
  rangeErrorKey,
  toWeeklyOperatingHours,
  toWeeklyTimeSlots,
  validateScheduleDraft,
  validateTimeSlotsDraft,
  type DayScheduleDraft,
} from './weeklySchedule'
import type { DailySchedule } from './types'

function openMonday(
  overrides: Partial<DayScheduleDraft> = {},
): DayScheduleDraft[] {
  return createScheduleDraft().map((day) =>
    day.dayOfWeek === 'MONDAY'
      ? {
          ...day,
          open: true,
          businessHours: [{ startTime: '09:00', endTime: '22:00' }],
          ...overrides,
        }
      : day,
  )
}

describe('주간 스케줄 초안', () => {
  it('휴무 요일도 요일 자체는 전체 주간 본문에 포함한다', () => {
    const days = toWeeklyOperatingHours(openMonday())

    // 계약이 월~일 전체를 받는다. 일부 요일을 빼면 서버가 병합해 주지 않는다.
    expect(days).toHaveLength(7)
    expect(days.map((day) => day.dayOfWeek)).toContain('SUNDAY')
    expect(days.find((day) => day.dayOfWeek === 'SUNDAY')).toEqual({
      dayOfWeek: 'SUNDAY',
      businessHours: [],
      breakTimes: [],
    })
  })

  it('휴무로 되돌린 요일의 입력값은 본문에서 빈 배열이 된다', () => {
    const week = openMonday().map((day) =>
      day.dayOfWeek === 'MONDAY' ? { ...day, open: false } : day,
    )

    const monday = toWeeklyOperatingHours(week).find(
      (day) => day.dayOfWeek === 'MONDAY',
    )
    expect(monday?.businessHours).toEqual([])
  })

  it('영업일인데 구간이 없으면 오류다', () => {
    const week = createScheduleDraft().map((day) =>
      day.dayOfWeek === 'TUESDAY' ? { ...day, open: true } : day,
    )

    expect(validateScheduleDraft(week).TUESDAY).toBe(
      '영업일로 두려면 영업 구간을 최소 한 개 입력해 주세요.',
    )
  })

  it('시작이 종료보다 늦으면 오류다', () => {
    const week = openMonday({
      businessHours: [{ startTime: '22:00', endTime: '09:00' }],
    })

    expect(validateScheduleDraft(week)[rangeErrorKey('MONDAY', 'business', 0)]).toBe(
      '시작 시각이 종료 시각보다 앞서야 합니다.',
    )
  })

  it('반열린 구간이므로 앞 구간의 종료와 뒤 구간의 시작이 같으면 겹침이 아니다', () => {
    const week = openMonday({
      businessHours: [
        { startTime: '09:00', endTime: '14:00' },
        { startTime: '14:00', endTime: '22:00' },
      ],
    })

    expect(validateScheduleDraft(week)).toEqual({})
  })

  it('실제로 겹치는 구간은 오류다', () => {
    const week = openMonday({
      businessHours: [
        { startTime: '09:00', endTime: '15:00' },
        { startTime: '14:00', endTime: '22:00' },
      ],
    })

    expect(validateScheduleDraft(week)[rangeErrorKey('MONDAY', 'business', 0)]).toBe(
      '같은 요일의 다른 구간과 겹칩니다.',
    )
  })

  it('휴게시간이 영업 구간 밖이면 오류다', () => {
    const week = openMonday({
      breakTimes: [{ startTime: '23:00', endTime: '23:30' }],
    })

    expect(validateScheduleDraft(week)[rangeErrorKey('MONDAY', 'break', 0)]).toBe(
      '휴게시간은 영업 구간 안에 있어야 합니다.',
    )
  })

  it('휴게시간이 영업 구간 안이면 통과한다', () => {
    const week = openMonday({
      breakTimes: [{ startTime: '15:00', endTime: '17:00' }],
    })

    expect(validateScheduleDraft(week)).toEqual({})
  })
})

describe('예약 접수 시간대 초안', () => {
  const publishedHours: DailySchedule[] = [
    {
      dayOfWeek: 'MONDAY',
      businessHours: [{ startTime: '11:00', endTime: '22:00' }],
      breakTimes: [{ startTime: '15:00', endTime: '17:00' }],
    },
  ]

  function openSlots(slots: { startTime: string; endTime: string }[]) {
    return createTimeSlotsDraft().map((day) =>
      day.dayOfWeek === 'MONDAY' ? { ...day, open: true, slots } : day,
    )
  }

  it('접수 요일도 전체 주간으로 제출한다', () => {
    expect(toWeeklyTimeSlots(openSlots([]))).toHaveLength(7)
  })

  it('접수 요일에 시간대가 없으면 오류다', () => {
    expect(validateTimeSlotsDraft(openSlots([])).MONDAY).toBe(
      '접수하는 요일로 두려면 시간대를 최소 한 개 입력해 주세요.',
    )
  })

  it('게시된 영업시간 밖이면 경고한다', () => {
    const warnings = findTimeSlotConflicts(
      openSlots([{ startTime: '09:00', endTime: '10:00' }]),
      publishedHours,
    )

    expect(warnings[rangeErrorKey('MONDAY', 'slot', 0)]).toBe(
      '게시된 영업시간 밖입니다.',
    )
  })

  it('게시된 휴게시간과 겹치면 경고한다', () => {
    const warnings = findTimeSlotConflicts(
      openSlots([{ startTime: '14:00', endTime: '16:00' }]),
      publishedHours,
    )

    expect(warnings[rangeErrorKey('MONDAY', 'slot', 0)]).toBe(
      '게시된 휴게시간과 겹칩니다.',
    )
  })

  it('게시된 영업시간 안이면 경고하지 않는다', () => {
    const warnings = findTimeSlotConflicts(
      openSlots([{ startTime: '11:00', endTime: '14:00' }]),
      publishedHours,
    )

    expect(warnings).toEqual({})
  })

  it('게시된 영업시간에 없는 요일은 경고로만 알린다', () => {
    const week = createTimeSlotsDraft().map((day) =>
      day.dayOfWeek === 'FRIDAY'
        ? {
            ...day,
            open: true,
            slots: [{ startTime: '11:00', endTime: '12:00' }],
          }
        : day,
    )

    // 경고는 제출을 막지 않는다. 최종 판정은 서버가 한다.
    expect(findTimeSlotConflicts(week, publishedHours).FRIDAY).toBe(
      '게시된 영업시간에 금요일이 없습니다.',
    )
    expect(validateTimeSlotsDraft(week)).toEqual({})
  })
})
