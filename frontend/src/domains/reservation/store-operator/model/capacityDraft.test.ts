import { describe, expect, it } from 'vitest'
import {
  bucketErrorKey,
  createBucketDraft,
  toCapacityBuckets,
  validateCapacityDraft,
  type CapacityBucketDraft,
} from './capacityDraft'

function bucket(overrides: Partial<CapacityBucketDraft> = {}): CapacityBucketDraft {
  return {
    ...createBucketDraft(),
    startTime: '11:00',
    endTime: '14:00',
    maxPeople: '40',
    maxTeams: '10',
    minPartySize: '1',
    maxPartySize: '8',
    ...overrides,
  }
}

describe('예약 수용량 초안 검증', () => {
  it('구간이 없으면 저장하지 않는다', () => {
    expect(validateCapacityDraft([]).buckets).toBe(
      '구간을 최소 한 개 입력해 주세요.',
    )
  })

  it('맞는 값이면 통과한다', () => {
    expect(validateCapacityDraft([bucket()])).toEqual({})
  })

  it('반열린 구간이라 끝과 시작이 같으면 겹침이 아니다', () => {
    const buckets = [
      bucket({ startTime: '11:00', endTime: '14:00' }),
      bucket({ startTime: '14:00', endTime: '17:00' }),
    ]

    expect(validateCapacityDraft(buckets)).toEqual({})
  })

  it('겹치는 구간은 오류다', () => {
    const buckets = [
      bucket({ startTime: '11:00', endTime: '15:00' }),
      bucket({ startTime: '14:00', endTime: '17:00' }),
    ]

    expect(validateCapacityDraft(buckets)[bucketErrorKey(0, 'time')]).toBe(
      '다른 구간과 겹칩니다.',
    )
  })

  it('최대 인원은 1 이상이다', () => {
    expect(
      validateCapacityDraft([bucket({ maxPeople: '0' })])[
        bucketErrorKey(0, 'maxPeople')
      ],
    ).toContain('1 이상')
  })

  it('최대 팀 수는 0을 허용한다', () => {
    expect(
      validateCapacityDraft([bucket({ maxTeams: '0' })])[
        bucketErrorKey(0, 'maxTeams')
      ],
    ).toBeUndefined()
  })

  it('예약 최소 인원이 최대보다 크면 오류다', () => {
    expect(
      validateCapacityDraft([bucket({ minPartySize: '9', maxPartySize: '4' })])[
        bucketErrorKey(0, 'maxPartySize')
      ],
    ).toBe('최대 예약 인원이 최소 인원보다 크거나 같아야 합니다.')
  })

  it('제출 본문은 숫자로 바꿔 담는다', () => {
    expect(toCapacityBuckets([bucket({ infantsAllowed: true })])).toEqual([
      {
        startTime: '11:00',
        endTime: '14:00',
        maxPeople: 40,
        maxTeams: 10,
        minPartySize: 1,
        maxPartySize: 8,
        infantsAllowed: true,
      },
    ])
  })
})
