import type { CapacityBucketRequest } from './types'

/**
 * 예약 수용량 편집 모델.
 *
 * `PUT .../reservation-capacities/{serviceDate}`는 그 날짜의 버킷 **전체**를
 * 교체한다. 부분 수정이 아니다. 그래서 편집 모델도 "그 날짜에 남길 전체 목록"을
 * 들고 있고, 화면은 이 사실을 저장 전에 분명히 알린다.
 */

const TIME_PATTERN = /^([01][0-9]|2[0-3]):[0-5][0-9]$/
export const MAX_BUCKETS = 96
const MAX_PEOPLE = 10_000
const MAX_TEAMS = 10_000
const MAX_PARTY_SIZE = 100

/** 숫자 입력을 문자열로 들고 있다가 제출 시점에 정수로 바꾼다. */
export interface CapacityBucketDraft {
  startTime: string
  endTime: string
  maxPeople: string
  maxTeams: string
  minPartySize: string
  maxPartySize: string
  infantsAllowed: boolean
}

export function createBucketDraft(): CapacityBucketDraft {
  return {
    startTime: '',
    endTime: '',
    maxPeople: '',
    maxTeams: '',
    minPartySize: '1',
    maxPartySize: '',
    infantsAllowed: false,
  }
}

function toMinutes(time: string): number | null {
  if (!TIME_PATTERN.test(time)) {
    return null
  }
  const [hour, minute] = time.split(':')
  return Number(hour) * 60 + Number(minute)
}

function integerInRange(
  value: string,
  minimum: number,
  maximum: number,
): number | null {
  if (value.trim().length === 0) {
    return null
  }
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    return null
  }
  return parsed
}

export function bucketErrorKey(index: number, field: string): string {
  return `${index}.${field}`
}

/**
 * 버킷 목록을 검증한다.
 *
 * 시간 구간은 `[startTime, endTime)`이라 앞 구간의 끝과 뒤 구간의 시작이 같은
 * 것은 겹침이 아니다. 서버도 같은 판정을 하며 최종 확정은 서버가 한다.
 */
export function validateCapacityDraft(
  buckets: readonly CapacityBucketDraft[],
): Readonly<Record<string, string>> {
  const errors: Record<string, string> = {}

  if (buckets.length === 0) {
    errors.buckets = '구간을 최소 한 개 입력해 주세요.'
    return errors
  }
  if (buckets.length > MAX_BUCKETS) {
    errors.buckets = `구간은 최대 ${MAX_BUCKETS}개입니다.`
  }

  buckets.forEach((bucket, index) => {
    const start = toMinutes(bucket.startTime)
    const end = toMinutes(bucket.endTime)

    if (start === null || end === null) {
      errors[bucketErrorKey(index, 'time')] =
        '시각을 HH:MM 형식으로 입력해 주세요.'
    } else if (start >= end) {
      errors[bucketErrorKey(index, 'time')] =
        '시작 시각이 종료 시각보다 앞서야 합니다.'
    } else {
      const overlapping = buckets.some((other, otherIndex) => {
        if (otherIndex === index) {
          return false
        }
        const otherStart = toMinutes(other.startTime)
        const otherEnd = toMinutes(other.endTime)
        if (otherStart === null || otherEnd === null) {
          return false
        }
        return start < otherEnd && otherStart < end
      })
      if (overlapping) {
        errors[bucketErrorKey(index, 'time')] = '다른 구간과 겹칩니다.'
      }
    }

    if (integerInRange(bucket.maxPeople, 1, MAX_PEOPLE) === null) {
      errors[bucketErrorKey(index, 'maxPeople')] =
        `최대 인원은 1 이상 ${MAX_PEOPLE} 이하 정수입니다.`
    }
    if (integerInRange(bucket.maxTeams, 0, MAX_TEAMS) === null) {
      errors[bucketErrorKey(index, 'maxTeams')] =
        `최대 팀 수는 0 이상 ${MAX_TEAMS} 이하 정수입니다.`
    }

    const minParty = integerInRange(bucket.minPartySize, 1, MAX_PARTY_SIZE)
    const maxParty = integerInRange(bucket.maxPartySize, 1, MAX_PARTY_SIZE)
    if (minParty === null) {
      errors[bucketErrorKey(index, 'minPartySize')] =
        `최소 인원은 1 이상 ${MAX_PARTY_SIZE} 이하 정수입니다.`
    }
    if (maxParty === null) {
      errors[bucketErrorKey(index, 'maxPartySize')] =
        `최대 예약 인원은 1 이상 ${MAX_PARTY_SIZE} 이하 정수입니다.`
    }
    if (minParty !== null && maxParty !== null && minParty > maxParty) {
      errors[bucketErrorKey(index, 'maxPartySize')] =
        '최대 예약 인원이 최소 인원보다 크거나 같아야 합니다.'
    }
  })

  return errors
}

/** 검증을 통과한 초안을 계약 본문으로 옮긴다. */
export function toCapacityBuckets(
  buckets: readonly CapacityBucketDraft[],
): CapacityBucketRequest[] {
  return buckets.map((bucket) => ({
    startTime: bucket.startTime,
    endTime: bucket.endTime,
    maxPeople: Number(bucket.maxPeople),
    maxTeams: Number(bucket.maxTeams),
    minPartySize: Number(bucket.minPartySize),
    maxPartySize: Number(bucket.maxPartySize),
    infantsAllowed: bucket.infantsAllowed,
  }))
}
