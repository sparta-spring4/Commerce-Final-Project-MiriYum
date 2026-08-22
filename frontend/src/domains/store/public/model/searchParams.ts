import type { components } from '../../../../shared/api/generated/store-search'

export type Region = components['schemas']['Region']
export type ReservationAvailability =
  components['schemas']['ReservationAvailability']
export type OperationStatus = components['schemas']['OperationStatus']
export type StoreSummary = components['schemas']['StoreSummary']
export type StoreDetail = components['schemas']['StoreDetail']
export type StoreModes = components['schemas']['StoreModes']
export type PublicMenu = components['schemas']['PublicMenu']
export type CatalogItem = components['schemas']['CatalogItem']
export type DailySchedule = components['schemas']['DailySchedule']
export type DailyTimeSlots = components['schemas']['DailyTimeSlots']

/**
 * 계약이 허용하는 지역은 광역 5개뿐이다.
 * 시안의 동 단위 표기는 1차 MVP에서 광역으로 대체한다(Epic #190 지역 단위 결정).
 */
export const REGIONS: readonly Region[] = [
  'SEOUL',
  'BUSAN',
  'DAEGU',
  'DAEJEON',
  'GWANGJU',
]

const REGION_SET = new Set<string>(REGIONS)

export function isRegion(value: string): value is Region {
  return REGION_SET.has(value)
}

/** 계약이 허용하는 정렬값. 그 밖의 값은 서버가 400을 반환한다. */
export const SORT_OPTIONS = [
  'name,asc',
  'name,desc',
  'createdAt,desc',
  'createdAt,asc',
] as const

export type SortOption = (typeof SORT_OPTIONS)[number]

const SORT_SET = new Set<string>(SORT_OPTIONS)

export const DEFAULT_SORT: SortOption = 'name,asc'
export const DEFAULT_PAGE_SIZE = 20
export const PAGE_SIZE_OPTIONS = [10, 20, 50] as const
export type PageSize = (typeof PAGE_SIZE_OPTIONS)[number]
const PAGE_SIZE_SET = new Set<number>(PAGE_SIZE_OPTIONS)

/** 계약의 partySize 범위. */
export const MIN_PARTY_SIZE = 1
export const MAX_PARTY_SIZE = 100

/** 계약의 keyword 길이. */
const MAX_KEYWORD_LENGTH = 100
const MAX_SEARCH_INPUT_LENGTH = 100
const MAX_CURSOR_LENGTH = 1024

/**
 * 화면이 다루는 검색 조건. URL search params가 유일한 원본이고
 * 이 타입은 그 문자열을 해석한 결과다. 서버에 보낼 값은 toSearchQuery가 만든다.
 */
export interface StoreSearchFilters {
  keyword: string
  region: Region | null
  storeCategoryCode: string | null
  serviceDate: string
  startTime: string
  /** 빈 문자열은 미입력이다. 임의 기본 인원을 만들지 않는다. */
  partySize: string
  includesInfants: boolean
  availableOnly: boolean
  page: number
  size: PageSize
  cursor: string
  sort: SortOption
}

export const EMPTY_FILTERS: StoreSearchFilters = {
  keyword: '',
  region: null,
  storeCategoryCode: null,
  serviceDate: '',
  startTime: '',
  partySize: '',
  includesInfants: false,
  availableOnly: false,
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  cursor: '',
  sort: DEFAULT_SORT,
}

const LOCAL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const LOCAL_TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/
const CATALOG_CODE_PATTERN = /^[A-Z][A-Z0-9_]{1,49}$/

/**
 * URL 문자열을 필터로 읽는다.
 *
 * 사용자가 주소창을 고치거나 오래된 링크를 열 수 있으므로 계약에 맞지 않는 값은
 * 조용히 통과시키지 않고 미입력으로 되돌린다. 서버에 400을 유발할 값을
 * 프론트가 먼저 걸러 낸다.
 */
export function readFilters(search: URLSearchParams): StoreSearchFilters {
  const region = search.get('region')
  const category = search.get('storeCategoryCode')
  const sort = search.get('sort')
  const page = Number.parseInt(search.get('page') ?? '', 10)
  const size = Number.parseInt(search.get('size') ?? '', 10)

  return {
    keyword: (search.get('keyword') ?? '').slice(0, MAX_KEYWORD_LENGTH),
    region: region !== null && isRegion(region) ? region : null,
    storeCategoryCode:
      category !== null && CATALOG_CODE_PATTERN.test(category) ? category : null,
    serviceDate: matchOrEmpty(search.get('serviceDate'), LOCAL_DATE_PATTERN),
    startTime: matchOrEmpty(search.get('startTime'), LOCAL_TIME_PATTERN),
    partySize: readPartySize(search.get('partySize')),
    includesInfants: search.get('includesInfants') === 'true',
    availableOnly: search.get('availableOnly') === 'true',
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: PAGE_SIZE_SET.has(size) ? (size as PageSize) : DEFAULT_PAGE_SIZE,
    cursor: (search.get('cursor') ?? '').slice(0, MAX_CURSOR_LENGTH),
    sort: sort !== null && SORT_SET.has(sort) ? (sort as SortOption) : DEFAULT_SORT,
  }
}

function matchOrEmpty(value: string | null, pattern: RegExp): string {
  return value !== null && pattern.test(value) ? value : ''
}

function readPartySize(value: string | null): string {
  if (value === null) {
    return ''
  }
  const parsed = Number.parseInt(value, 10)
  if (
    !Number.isInteger(parsed) ||
    parsed < MIN_PARTY_SIZE ||
    parsed > MAX_PARTY_SIZE
  ) {
    return ''
  }
  return String(parsed)
}

/** 필터를 URL search params로 되돌린다. 기본값은 주소를 짧게 유지하려 생략한다. */
export function writeFilters(filters: StoreSearchFilters): URLSearchParams {
  const search = new URLSearchParams()

  appendIf(search, 'keyword', filters.keyword.trim())
  appendIf(search, 'region', filters.region)
  appendIf(search, 'storeCategoryCode', filters.storeCategoryCode)
  appendIf(search, 'serviceDate', filters.serviceDate)
  appendIf(search, 'startTime', filters.startTime)
  appendIf(search, 'partySize', filters.partySize)
  if (filters.includesInfants) {
    search.append('includesInfants', 'true')
  }
  if (filters.availableOnly) {
    search.append('availableOnly', 'true')
  }
  if (filters.page > 0) {
    search.append('page', String(filters.page))
  }
  if (filters.size !== DEFAULT_PAGE_SIZE) {
    search.append('size', String(filters.size))
  }
  appendIf(search, 'cursor', filters.cursor)
  if (filters.sort !== DEFAULT_SORT) {
    search.append('sort', filters.sort)
  }

  return search
}

function appendIf(
  search: URLSearchParams,
  name: string,
  value: string | null,
): void {
  if (value !== null && value.length > 0) {
    search.append(name, value)
  }
}

/** 예약 조건 세 값의 입력 상태. 서버 호출 가능 여부를 이걸로 판정한다. */
export type ReservationConditionState = 'none' | 'partial' | 'complete'

/**
 * 예약 조건은 셋을 모두 보내거나 하나도 보내지 않아야 한다.
 *
 * 계약은 일부만 보낸 요청을 `COMMON_001`로 거절하므로 화면이 부분 조건을
 * 서버로 넘기지 않는다. 임의 기본 날짜·시간·인원으로 채우지도 않는다.
 */
export function reservationConditionState(
  filters: StoreSearchFilters,
): ReservationConditionState {
  const filled = [
    filters.serviceDate,
    filters.startTime,
    filters.partySize,
  ].filter((value) => value.length > 0).length

  if (filled === 0) {
    return 'none'
  }
  return filled === 3 ? 'complete' : 'partial'
}

/** 예약 조건이 완전할 때만 예약 가능 매장만 보기를 허용한다. */
export function canRequestAvailableOnly(filters: StoreSearchFilters): boolean {
  return reservationConditionState(filters) === 'complete'
}

/** `GET /api/v1/stores`에 보낼 query. 계약에 없는 이름을 만들지 않는다. */
export type StoreSearchQuery = Record<
  string,
  string | number | boolean | undefined
>

/**
 * 필터를 서버 query로 옮긴다.
 *
 * 부분 예약 조건은 서버가 400으로 거절하므로 아예 싣지 않는다. 화면은 이 사실을
 * 사용자에게 별도로 안내하고 여기서 조용히 기본값을 채우지 않는다.
 */
export function toSearchQuery(filters: StoreSearchFilters): StoreSearchQuery {
  const complete = reservationConditionState(filters) === 'complete'
  const structuredTerms = [
    filters.region,
    filters.storeCategoryCode,
    ...(complete
      ? [
          filters.serviceDate,
          filters.startTime,
          `${filters.partySize}명`,
        ]
      : []),
  ]
    .filter((value): value is string => value !== null && value.length > 0)
    .join(' ')
  const keywordLimit = Math.max(
    0,
    MAX_SEARCH_INPUT_LENGTH -
      structuredTerms.length -
      (structuredTerms.length > 0 ? 1 : 0),
  )
  const keyword = filters.keyword.trim().slice(0, keywordLimit)
  const searchInput = [keyword, structuredTerms]
    .filter((value) => value.length > 0)
    .join(' ')
  const integrated = searchInput.length > 0

  return {
    searchInput: integrated ? searchInput : undefined,
    includesInfants: complete && filters.includesInfants ? true : undefined,
    availableOnly: complete && filters.availableOnly ? true : undefined,
    page: !integrated && filters.page > 0 ? filters.page : undefined,
    cursor: integrated && filters.cursor.length > 0 ? filters.cursor : undefined,
    size: filters.size,
    sort: integrated || filters.sort !== DEFAULT_SORT ? filters.sort : undefined,
  }
}

/**
 * 매장 상세의 가용성 조회 query.
 * 목록과 같은 규칙으로 완전한 조건일 때만 싣는다.
 */
export function toDetailQuery(filters: StoreSearchFilters): StoreSearchQuery {
  if (reservationConditionState(filters) !== 'complete') {
    return {}
  }
  return {
    serviceDate: filters.serviceDate,
    startTime: filters.startTime,
    partySize: Number(filters.partySize),
    includesInfants: filters.includesInfants ? true : undefined,
  }
}
