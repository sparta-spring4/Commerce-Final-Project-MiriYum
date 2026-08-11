import { useSearchParams } from 'react-router'
import { EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Pagination } from '../../../shared/ui/Pagination'
import { hasErrorCode } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { toDisplayNameMap, useCatalog, useStoreSearch } from '../api/queries'
import {
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
      <header className="store-search__header">
        <h1>매장 찾기</h1>
        <p>
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
      <p className="store-search__count" aria-live="polite">
        {`전체 ${page.totalElements}곳 가운데 ${items.length}곳을 표시합니다.`}
      </p>
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
