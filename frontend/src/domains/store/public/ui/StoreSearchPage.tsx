import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Icon, type IconName } from '../../../../shared/ui/Icon'
import { Pagination } from '../../../../shared/ui/Pagination'
import { Button } from '../../../../shared/ui/Button'
import { hasErrorCode } from '../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../shared/api/envelope'
import { toDisplayNameMap, useCatalog, useStoreSearch } from '../api/queries'
import { KakaoMap } from '../map/KakaoMap'
import { MapFallback } from '../map/MapFallback'
import type { MapStore } from '../map/map.types'
import { REGION_LABEL, catalogLabel } from '../model/labels'
import {
  readMapOpenPreference,
  writeMapOpenPreference,
} from '../model/mapPreference'
import { mapStoreOrdinal, toMapStores } from '../model/mapStores'
import {
  SORT_OPTIONS,
  PAGE_SIZE_OPTIONS,
  readFilters,
  reservationConditionState,
  toSearchQuery,
  writeFilters,
  type StoreSearchFilters,
} from '../model/searchParams'
import { SearchFilterForm } from './SearchFilterForm'
import { StoreCard } from './StoreCard'

/** 지도 칸의 id. 여는 버튼이 `aria-controls`로 가리킨다. */
const MAP_PANEL_ID = 'store-search-map'

/**
 * 매장 찾기 결과 화면.
 *
 * URL search params가 필터의 유일한 원본이다. 컴포넌트 state로 따로 들고 있으면
 * 뒤로가기와 링크 공유가 화면 상태와 어긋난다.
 *
 * 반대로 지도를 열어 뒀는지와 지도에서 짚고 있는 매장은 URL에 두지 않는다.
 * 공유한 링크가 상대에게 남의 보기 방식을 강요할 이유가 없고, 뒤로가기가
 * 검색 조건이 아니라 카드 선택을 되돌리게 된다.
 *
 * 지도는 기본으로 열지 않는다. 검색 결과는 목록이 본체이고 지도는 위치가
 * 궁금할 때 여는 도구다. 다만 열어 둔 선택은 기억한다(`mapPreference`).
 */
export function StoreSearchPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = readFilters(searchParams)

  const categories = useCatalog('store-categories')
  const categoryNames = toDisplayNameMap(categories.data)

  const query = toSearchQuery(filters)
  const search = useStoreSearch(query)

  // 저장된 기본값은 첫 렌더에서 한 번만 읽는다.
  const [isMapOpen, setIsMapOpen] = useState(readMapOpenPreference)
  const [selectedStoreId, setSelectedStoreId] = useState<string | null>(null)

  const items = search.data?.items
  /*
   * 지도가 매장에 대해 아는 전부. 검색 응답을 지도에 직접 넘기지 않고 이
   * 경계 하나만 지난다. 좌표가 실제로 들어오는 지점도 여기다.
   */
  const mapStores = useMemo(() => toMapStores(items ?? []), [items])

  /*
   * 선택은 지금 화면에 있는 매장에 대해서만 뜻이 있다. 조건을 바꾸거나 다음
   * 페이지로 넘어가 사라진 매장의 id를 그대로 들고 있으면 지도는 아무 데도
   * 없는 매장을 선택됐다고 말하게 된다. 새 결과가 확정되기 전에는 파생값으로
   * 숨기고, 확정된 결과에 없으면 원본 state도 지워 이후 재등장을 선택으로
   * 오인하지 않게 한다.
   */
  const selectedInResults =
    selectedStoreId !== null &&
    mapStores.some((store: MapStore) => store.storeId === selectedStoreId)
      ? selectedStoreId
      : null

  useEffect(() => {
    if (search.isSuccess && selectedStoreId !== selectedInResults) {
      setSelectedStoreId(null)
    }
  }, [search.isSuccess, selectedInResults, selectedStoreId])

  function applyFilters(next: StoreSearchFilters) {
    setSearchParams(writeFilters({ ...next, cursor: '' }))
  }

  /**
   * 지도를 열고 닫는 유일한 통로.
   *
   * 화면 상태와 저장된 기본값이 어긋나지 않도록 한자리에서 함께 바꾼다.
   */
  function setMapOpen(next: boolean) {
    setIsMapOpen(next)
    writeMapOpenPreference(next)
  }

  /** 목록 카드에서 올라온 요청. 지도가 닫혀 있으면 함께 연다. */
  function showOnMap(storeId: string) {
    setSelectedStoreId(storeId)
    setMapOpen(true)
  }

  function goToPage(page: number) {
    applyFilters({ ...filters, page })
  }

  function goToCursor(cursor: string) {
    setSearchParams(writeFilters({ ...filters, page: 0, cursor }))
  }

  const detailSearch = writeFilters({
    ...filters,
    page: 0,
    cursor: '',
  }).toString()
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
                ? 'page' in search.data
                  ? `총 ${search.data.page.totalElements}개의 매장`
                  : `현재 ${search.data.items.length}개의 매장`
                : '매장을 찾는 중입니다.'}
            </p>

            <div className="store-search__view-controls">
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

              <label className="store-search__page-size">
                <span className="visually-hidden">한 페이지 표시 개수</span>
                <select
                  value={filters.size}
                  onChange={(event) =>
                    applyFilters({
                      ...filters,
                      page: 0,
                      cursor: '',
                      size: Number(event.target.value) as StoreSearchFilters['size'],
                    })
                  }
                >
                  {PAGE_SIZE_OPTIONS.map((size) => (
                    <option key={size} value={size}>
                      {size}개
                    </option>
                  ))}
                </select>
              </label>

              <MapToggle isOpen={isMapOpen} onToggle={setMapOpen} />
            </div>
          </div>

          {/*
            지도가 닫혀 있으면 아예 그리지 않는다. 감춰 두기만 하면 보지도 않을
            지도 때문에 Kakao SDK를 내려받는다.

            넓은 화면은 지도가 열리면 목록 옆에 붙고, 좁은 화면은 지도가 자리를
            다 쓰도록 목록을 CSS로 감춘다. 감춘 쪽은 display:none이라 접근성
            트리에서도 함께 빠진다.
          */}
          <div
            className="store-search__panes"
            data-map-open={isMapOpen ? 'true' : 'false'}
          >
            <div className="store-search__pane store-search__pane--list">
              <SearchResults
                conditionState={conditionState}
                search={search}
                categoryNames={categoryNames}
                detailSearch={detailSearch}
                selectedStoreId={selectedInResults}
                onShowOnMap={showOnMap}
                onRetry={() => void search.refetch()}
                onPageChange={goToPage}
                onCursorChange={goToCursor}
              />
            </div>

            {isMapOpen && (
              <div
                className="store-search__pane store-search__pane--map"
                id={MAP_PANEL_ID}
              >
                <div className="store-search__map-head">
                  <h2 className="store-search__map-title">지도</h2>
                  {/*
                    지도는 sticky라 목록을 내리면 위쪽 여닫기 버튼이 화면 밖으로
                    나간다. 지도 옆에도 닫을 길을 둔다. 위 버튼과 이름이 겹치면
                    화면 낭독기에서 두 버튼을 구분할 수 없어 다르게 부른다.
                  */}
                  <button
                    type="button"
                    className="store-search__map-close"
                    onClick={() => setMapOpen(false)}
                  >
                    <Icon name="close" className="mi-icon--sm" />
                    지도 영역 닫기
                  </button>
                </div>
                <MapPane
                  search={search}
                  stores={mapStores}
                  selectedStoreId={selectedInResults}
                  onSelectStore={setSelectedStoreId}
                />
              </div>
            )}
          </div>
        </section>
      </div>
    </div>
  )
}

/**
 * 지도를 여닫는 버튼.
 *
 * 눌림(`aria-pressed`)이 아니라 펼침(`aria-expanded`)이다. 이 버튼은 두 보기
 * 중 하나를 고르는 것이 아니라 지도라는 칸을 열고 닫는다. `aria-controls`가
 * 무엇이 열리는지 가리키므로 두 화면 폭 모두에서 사실이다.
 *
 * 다시 누르면 닫히고, 그 선택은 다음 방문까지 기억된다.
 */
function MapToggle({
  isOpen,
  onToggle,
}: {
  isOpen: boolean
  onToggle: (next: boolean) => void
}) {
  return (
    <button
      type="button"
      className="store-search__map-toggle"
      aria-expanded={isOpen}
      aria-controls={MAP_PANEL_ID}
      onClick={() => onToggle(!isOpen)}
    >
      <Icon name="pin" className="mi-icon--sm" />
      {isOpen ? '지도 닫기' : '지도 보기'}
    </button>
  )
}

/**
 * 지도 자리.
 *
 * 지도 자체의 상태(키 미설정·SDK 실패·좌표 없음·결과 없음)는 `KakaoMap`이
 * 이미 `MapFallback`으로 구분해 알린다. 여기서는 그 앞 단계, 곧 검색이 아직
 * 끝나지 않았거나 실패한 구간만 가린다. 그 구간을 그대로 넘기면 지도가 아직
 * 오지 않은 결과를 "검색 결과가 없습니다"로 잘못 단정한다.
 */
function MapPane({
  search,
  stores,
  selectedStoreId,
  onSelectStore,
}: {
  search: ReturnType<typeof useStoreSearch>
  stores: MapStore[]
  selectedStoreId: string | null
  onSelectStore: (storeId: string) => void
}) {
  if (search.isPending) {
    return <Loading label="지도를 준비하는 중입니다." />
  }

  if (search.isError) {
    return (
      <MapFallback
        reason="검색 결과를 불러오지 못했습니다."
        guidance="검색을 다시 시도하면 지도도 함께 표시됩니다."
      />
    )
  }

  return (
    <KakaoMap
      stores={stores}
      selectedStoreId={selectedStoreId}
      onSelectStore={onSelectStore}
    />
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
  /** 지도에서 짚고 있는 매장. 목록에도 같은 사실을 표시한다. */
  selectedStoreId: string | null
  onShowOnMap: (storeId: string) => void
  onRetry: () => void
  onPageChange: (page: number) => void
  onCursorChange: (cursor: string) => void
}

function SearchResults({
  conditionState,
  search,
  categoryNames,
  detailSearch,
  selectedStoreId,
  onShowOnMap,
  onRetry,
  onPageChange,
  onCursorChange,
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

  const response = search.data
  const { items } = response
  const nextCursor = 'page' in response ? null : response.nextCursor

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
        {items.map((store, index) => (
          <StoreCard
            key={store.storeId}
            store={store}
            categoryNames={categoryNames}
            detailSearch={detailSearch}
            /* 마커 이름도 같은 번호로 시작한다(`toMapStores`). */
            ordinal={mapStoreOrdinal(index)}
            isSelected={store.storeId === selectedStoreId}
            onShowOnMap={onShowOnMap}
          />
        ))}
      </ul>
      {'page' in response ? (
        <Pagination
          number={response.page.number}
          totalPages={response.page.totalPages}
          totalElements={response.page.totalElements}
          hasNext={response.page.hasNext}
          onChange={onPageChange}
        />
      ) : (
        nextCursor !== null && (
          <nav className="mi-pagination" aria-label="검색 결과 이동">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onCursorChange(nextCursor)}
            >
              다음 결과
            </Button>
          </nav>
        )
      )}
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
