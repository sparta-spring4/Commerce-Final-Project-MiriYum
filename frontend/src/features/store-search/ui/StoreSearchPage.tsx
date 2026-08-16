import { useSearchParams } from 'react-router'
import { EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Icon, type IconName } from '../../../shared/ui/Icon'
import { Pagination } from '../../../shared/ui/Pagination'
import { hasErrorCode } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { toDisplayNameMap, useCatalog, useStoreSearch } from '../api/queries'
import { REGION_LABEL, catalogLabel } from '../model/labels'
import {
  SORT_OPTIONS,
  readFilters,
  reservationConditionState,
  toSearchQuery,
  writeFilters,
  type StoreSearchFilters,
} from '../model/searchParams'
import { SearchFilterForm } from './SearchFilterForm'
import { StoreCard } from './StoreCard'

/**
 * 매장 찾기 결과 화면.
 *
 * URL search params가 필터의 유일한 원본이다. 컴포넌트 state로 따로 들고 있으면
 * 뒤로가기와 링크 공유가 화면 상태와 어긋난다.
 */
export function StoreSearchPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = readFilters(searchParams)

  const categories = useCatalog('store-categories')
  const categoryNames = toDisplayNameMap(categories.data)

  const query = toSearchQuery(filters)
  const search = useStoreSearch(query)

  function applyFilters(next: StoreSearchFilters) {
    setSearchParams(writeFilters(next))
  }

  function goToPage(page: number) {
    applyFilters({ ...filters, page })
  }

  const detailSearch = writeFilters({ ...filters, page: 0 }).toString()
  const conditionState = reservationConditionState(filters)

  return (
    <div className="mi-container store-search">
      <header className="mi-page-head">
        <h1 className="mi-page-head__title">매장 찾기</h1>
        <p className="mi-page-head__lead">
          키워드와 지역, 카테고리로 매장을 찾고 예약 조건을 함께 확인할 수 있습니다.
        </p>
      </header>

      <div className="store-search__layout">
        <aside className="store-search__filters" aria-label="검색 필터">
          <SearchFilterForm
            layout="full"
            value={filters}
            storeCategories={categories.data ?? []}
            onSubmit={applyFilters}
          />
          {categories.isError && (
            <ErrorState
              error={categories.error}
              message="카테고리 목록을 불러오지 못했습니다. 카테고리 없이도 검색할 수 있습니다."
              onRetry={() => void categories.refetch()}
            />
          )}
        </aside>

        <section className="store-search__results" aria-label="검색 결과">
          <ActiveConditions
            filters={filters}
            categoryNames={categoryNames}
            onClear={applyFilters}
          />

          <div className="store-search__results-head">
            <p className="store-search__count" aria-live="polite">
              {search.isSuccess
                ? `총 ${search.data.page.totalElements}개의 매장`
                : '매장을 찾는 중입니다.'}
            </p>

            {/*
              계약이 허용하는 정렬만 둔다. 시안의 "추천순·별점순·리뷰순"은
              서버가 지원하지 않는 값이라 400이 되므로 만들지 않는다.
            */}
            <label className="store-search__sort">
              <span className="visually-hidden">정렬</span>
              <select
                value={filters.sort}
                onChange={(event) =>
                  applyFilters({
                    ...filters,
                    page: 0,
                    sort: event.target.value as StoreSearchFilters['sort'],
                  })
                }
              >
                {SORT_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {SORT_LABEL[option]}
                  </option>
                ))}
              </select>
              <Icon name="chevronRight" className="mi-icon--sm" />
            </label>
          </div>

          <SearchResults
            conditionState={conditionState}
            search={search}
            categoryNames={categoryNames}
            detailSearch={detailSearch}
            onRetry={() => void search.refetch()}
            onPageChange={goToPage}
          />
        </section>
      </div>
    </div>
  )
}

/** 계약의 정렬값 표시명. 값 자체는 searchParams가 소유한다. */
const SORT_LABEL: Record<StoreSearchFilters['sort'], string> = {
  'name,asc': '이름 오름차순',
  'name,desc': '이름 내림차순',
  'createdAt,desc': '최근 등록순',
  'createdAt,asc': '먼저 등록순',
}

/**
 * 지금 걸려 있는 조건을 알약으로 요약한다.
 *
 * 시안 `_4`의 "예약 조건:" 줄이다. 각 알약의 × 는 그 조건 하나만 지운다.
 * 조건이 하나도 없으면 빈 줄을 남기지 않고 아예 그리지 않는다.
 */
function ActiveConditions({
  filters,
  categoryNames,
  onClear,
}: {
  filters: StoreSearchFilters
  categoryNames: ReadonlyMap<string, string>
  onClear: (next: StoreSearchFilters) => void
}) {
  const chips: { key: string; icon: IconName; text: string; clear: () => void }[] =
    []

  if (filters.keyword.length > 0) {
    chips.push({
      key: 'keyword',
      icon: 'search',
      text: filters.keyword,
      clear: () => onClear({ ...filters, keyword: '', page: 0 }),
    })
  }
  if (filters.region !== null) {
    chips.push({
      key: 'region',
      icon: 'pin',
      text: REGION_LABEL[filters.region],
      clear: () => onClear({ ...filters, region: null, page: 0 }),
    })
  }
  if (filters.storeCategoryCode !== null) {
    chips.push({
      key: 'category',
      icon: 'menu',
      text: catalogLabel(filters.storeCategoryCode, categoryNames),
      clear: () => onClear({ ...filters, storeCategoryCode: null, page: 0 }),
    })
  }
  if (filters.serviceDate.length > 0) {
    chips.push({
      key: 'serviceDate',
      icon: 'calendar',
      text: filters.serviceDate,
      clear: () => onClear({ ...filters, serviceDate: '', page: 0 }),
    })
  }
  if (filters.startTime.length > 0) {
    chips.push({
      key: 'startTime',
      icon: 'clock',
      text: filters.startTime,
      clear: () => onClear({ ...filters, startTime: '', page: 0 }),
    })
  }
  if (filters.partySize.length > 0) {
    chips.push({
      key: 'partySize',
      icon: 'group',
      text: `${filters.partySize}명`,
      clear: () => onClear({ ...filters, partySize: '', page: 0 }),
    })
  }

  if (chips.length === 0) {
    return null
  }

  return (
    <div className="store-search__conditions">
      <p className="store-search__conditions-label">적용한 조건</p>
      <ul className="store-search__condition-list">
        {chips.map((chip) => (
          <li key={chip.key} className="store-search__condition">
            <Icon name={chip.icon} className="mi-icon--sm" />
            <span>{chip.text}</span>
            <button
              type="button"
              className="store-search__condition-clear"
              aria-label={`${chip.text} 조건 지우기`}
              onClick={chip.clear}
            >
              <Icon name="close" className="mi-icon--sm" />
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

interface ResultsProps {
  conditionState: ReturnType<typeof reservationConditionState>
  search: ReturnType<typeof useStoreSearch>
  categoryNames: ReadonlyMap<string, string>
  detailSearch: string
  onRetry: () => void
  onPageChange: (page: number) => void
}

function SearchResults({
  conditionState,
  search,
  categoryNames,
  detailSearch,
  onRetry,
  onPageChange,
}: ResultsProps) {
  if (search.isPending) {
    return <Loading label="매장을 찾는 중입니다." />
  }

  if (search.isError) {
    return (
      <ErrorState
        error={search.error}
        message={searchErrorMessage(search.error)}
        onRetry={onRetry}
      />
    )
  }

  const { items, page } = search.data

  // 빈 배열은 오류가 아니라 정상 empty result다.
  if (items.length === 0) {
    return (
      <EmptyState
        title="조건에 맞는 매장이 없습니다."
        description={
          conditionState === 'complete'
            ? '날짜·시간·인원을 바꾸거나 지역·카테고리를 넓혀 보세요.'
            : '검색어나 지역, 카테고리를 바꿔 보세요.'
        }
      />
    )
  }

  return (
    <>
      {/* 총 개수는 결과 머리말이 이미 알린다. 여기서는 이 페이지 분량만 밝힌다. */}
      <p className="visually-hidden">{`이 페이지에 ${items.length}곳을 표시합니다.`}</p>
      <ul className="store-search__list">
        {items.map((store) => (
          <StoreCard
            key={store.storeId}
            store={store}
            categoryNames={categoryNames}
            detailSearch={detailSearch}
          />
        ))}
      </ul>
      <Pagination
        number={page.number}
        totalPages={page.totalPages}
        totalElements={page.totalElements}
        hasNext={page.hasNext}
        onChange={onPageChange}
      />
    </>
  )
}

/**
 * 서버 code로 분기한다. 오류 메시지 문자열을 파싱하지 않는다.
 * 판정할 수 없는 오류는 undefined를 돌려 공통 상태 문구를 쓰게 한다.
 */
function searchErrorMessage(error: unknown): string | undefined {
  if (hasErrorCode(error, CommonErrorCode.VALIDATION_FAILED)) {
    return '검색 조건이 올바르지 않습니다. 날짜·시간·인원을 다시 확인해 주세요.'
  }
  if (hasErrorCode(error, CommonErrorCode.TOO_MANY_REQUESTS)) {
    return '검색 요청이 많습니다. 잠시 후 다시 시도해 주세요.'
  }
  return undefined
}
