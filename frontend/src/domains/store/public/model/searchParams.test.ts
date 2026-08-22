import { describe, expect, it } from 'vitest'
import {
  DEFAULT_SORT,
  canRequestAvailableOnly,
  readFilters,
  reservationConditionState,
  toDetailQuery,
  toSearchQuery,
  writeFilters,
  EMPTY_FILTERS,
} from './searchParams'

function filters(overrides: Partial<typeof EMPTY_FILTERS> = {}) {
  return { ...EMPTY_FILTERS, ...overrides }
}

describe('readFilters', () => {
  it('URL 문자열을 필터로 읽는다', () => {
    const result = readFilters(
      new URLSearchParams(
        'keyword=파스타&region=SEOUL&storeCategoryCode=KOREAN&serviceDate=2026-09-01&startTime=19:00&partySize=2&includesInfants=true&availableOnly=true&page=2&sort=name,desc',
      ),
    )

    expect(result).toEqual({
      keyword: '파스타',
      region: 'SEOUL',
      storeCategoryCode: 'KOREAN',
      serviceDate: '2026-09-01',
      startTime: '19:00',
      partySize: '2',
      includesInfants: true,
      availableOnly: true,
      page: 2,
      cursor: '',
      sort: 'name,desc',
    })
  })

  it('계약에 없는 지역은 미입력으로 되돌린다', () => {
    // 서버가 400을 낼 값을 프론트가 먼저 걸러 낸다.
    expect(readFilters(new URLSearchParams('region=SEONGSU')).region).toBeNull()
  })

  it('허용하지 않는 정렬값은 기본 정렬로 되돌린다', () => {
    expect(readFilters(new URLSearchParams('sort=rating,desc')).sort).toBe(
      DEFAULT_SORT,
    )
  })

  it('범위를 벗어난 인원은 미입력으로 되돌린다', () => {
    expect(readFilters(new URLSearchParams('partySize=0')).partySize).toBe('')
    expect(readFilters(new URLSearchParams('partySize=101')).partySize).toBe('')
    expect(readFilters(new URLSearchParams('partySize=abc')).partySize).toBe('')
  })

  it('형식이 맞지 않는 날짜·시간은 미입력으로 되돌린다', () => {
    const result = readFilters(
      new URLSearchParams('serviceDate=2026-9-1&startTime=25:00'),
    )

    expect(result.serviceDate).toBe('')
    expect(result.startTime).toBe('')
  })

  it('음수 페이지는 첫 페이지로 되돌린다', () => {
    expect(readFilters(new URLSearchParams('page=-3')).page).toBe(0)
  })
})

describe('writeFilters', () => {
  it('읽은 값을 다시 쓰면 같은 조건이 유지된다', () => {
    const search = 'keyword=파스타&region=BUSAN&partySize=4'
    const roundTripped = readFilters(writeFilters(readFilters(new URLSearchParams(search))))

    expect(roundTripped.keyword).toBe('파스타')
    expect(roundTripped.region).toBe('BUSAN')
    expect(roundTripped.partySize).toBe('4')
  })

  it('기본값은 주소에 남기지 않는다', () => {
    expect(writeFilters(EMPTY_FILTERS).toString()).toBe('')
  })

  it('검색어 앞뒤 공백은 제거한다', () => {
    expect(writeFilters(filters({ keyword: '  파스타  ' })).get('keyword')).toBe(
      '파스타',
    )
  })
})

describe('reservationConditionState', () => {
  it('세 값이 모두 없으면 none이다', () => {
    expect(reservationConditionState(EMPTY_FILTERS)).toBe('none')
  })

  it('일부만 입력하면 partial이다', () => {
    expect(
      reservationConditionState(filters({ serviceDate: '2026-09-01' })),
    ).toBe('partial')
    expect(
      reservationConditionState(
        filters({ serviceDate: '2026-09-01', startTime: '19:00' }),
      ),
    ).toBe('partial')
  })

  it('세 값을 모두 입력하면 complete다', () => {
    expect(
      reservationConditionState(
        filters({
          serviceDate: '2026-09-01',
          startTime: '19:00',
          partySize: '2',
        }),
      ),
    ).toBe('complete')
  })
})

describe('toSearchQuery', () => {
  it('검색 조건을 하나의 자연어 통합 검색문으로 보낸다', () => {
    const query = toSearchQuery(
      filters({
        keyword: '얼큰한 국물',
        region: 'SEOUL',
        storeCategoryCode: 'KOREAN',
        serviceDate: '2026-09-01',
        startTime: '19:00',
        partySize: '2',
        includesInfants: true,
        availableOnly: true,
      }),
    )

    expect(query.searchInput).toBe(
      '얼큰한 국물 SEOUL KOREAN 2026-09-01 19:00 2명',
    )
    expect(query.keyword).toBeUndefined()
    expect(query.region).toBeUndefined()
    expect(query.storeCategoryCode).toBeUndefined()
    expect(query.serviceDate).toBeUndefined()
    expect(query.startTime).toBeUndefined()
    expect(query.partySize).toBeUndefined()
    expect(query.includesInfants).toBe(true)
    expect(query.availableOnly).toBe(true)
  })

  it('통합 검색 cursor를 URL에서 왕복 보존한다', () => {
    const next = filters({ keyword: '파스타', cursor: 'signed-cursor' })

    expect(readFilters(writeFilters(next)).cursor).toBe('signed-cursor')
  })

  it('부분 예약 조건은 통합 검색문에 포함하지 않는다', () => {
    const query = toSearchQuery(
      filters({ keyword: '파스타', serviceDate: '2026-09-01', partySize: '2' }),
    )

    expect(query.searchInput).toBe('파스타')
  })

  it('예약 조건이 불완전하면 availableOnly를 보내지 않는다', () => {
    const query = toSearchQuery(
      filters({ availableOnly: true, serviceDate: '2026-09-01' }),
    )

    expect(query.availableOnly).toBeUndefined()
  })

  it('검색 조건이 없으면 기존 전체 목록 조회를 유지한다', () => {
    const query = toSearchQuery(EMPTY_FILTERS)

    expect(query.searchInput).toBeUndefined()
    expect(query.page).toBeUndefined()
    expect(query.size).toBe(20)
    expect(query.availableOnly).toBeUndefined()
  })

  it('지역이나 카테고리만 선택해도 통합 검색을 사용한다', () => {
    const query = toSearchQuery(
      filters({ region: 'BUSAN', storeCategoryCode: 'JAPANESE' }),
    )

    expect(query.searchInput).toBe('BUSAN JAPANESE')
    expect(query).not.toHaveProperty('keyword')
    expect(query).not.toHaveProperty('region')
    expect(query).not.toHaveProperty('storeCategoryCode')
  })

  it('통합 검색은 화면에 표시된 기본 정렬도 명시해서 보낸다', () => {
    const query = toSearchQuery(filters({ keyword: '파스타' }))

    expect(query.sort).toBe('name,asc')
  })

  it('상세 조건을 보존하면서 통합 검색문의 100자 계약을 지킨다', () => {
    const query = toSearchQuery(
      filters({
        keyword: '가'.repeat(100),
        region: 'SEOUL',
        storeCategoryCode: 'KOREAN',
      }),
    )

    expect(query.searchInput).toBe(`${'가'.repeat(87)} SEOUL KOREAN`)
  })

  it('통합 검색에는 cursor만 보내고 page를 섞지 않는다', () => {
    const query = toSearchQuery(
      filters({ keyword: '파스타', page: 3, cursor: 'signed-cursor' }),
    )

    expect(query.cursor).toBe('signed-cursor')
    expect(query.page).toBeUndefined()
  })
})

describe('canRequestAvailableOnly', () => {
  it('예약 조건이 완전할 때만 허용한다', () => {
    expect(canRequestAvailableOnly(EMPTY_FILTERS)).toBe(false)
    expect(
      canRequestAvailableOnly(filters({ serviceDate: '2026-09-01' })),
    ).toBe(false)
    expect(
      canRequestAvailableOnly(
        filters({
          serviceDate: '2026-09-01',
          startTime: '19:00',
          partySize: '2',
        }),
      ),
    ).toBe(true)
  })
})

describe('toDetailQuery', () => {
  it('조건이 불완전하면 가용성을 요청하지 않는다', () => {
    expect(toDetailQuery(filters({ startTime: '19:00' }))).toEqual({})
  })

  it('조건이 완전하면 상세에도 같은 조건을 전달한다', () => {
    expect(
      toDetailQuery(
        filters({
          serviceDate: '2026-09-01',
          startTime: '19:00',
          partySize: '3',
        }),
      ),
    ).toEqual({
      serviceDate: '2026-09-01',
      startTime: '19:00',
      partySize: 3,
      includesInfants: undefined,
    })
  })
})
