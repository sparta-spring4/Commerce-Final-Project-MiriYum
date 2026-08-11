import { useNavigate } from 'react-router'
import { toDisplayNameMap, useCatalog } from '../api/queries'
import {
  EMPTY_FILTERS,
  writeFilters,
  type StoreSearchFilters,
} from '../model/searchParams'
import { SearchFilterForm } from './SearchFilterForm'

/**
 * 홈. 검색 진입점이다.
 *
 * 큐레이션·추천 목록을 두지 않는다. 1차 MVP 계약에 큐레이션 기준이 없고,
 * 이력 기반 추천은 2차 MVP 범위다. 시안의 "오늘의 추천 맛집" 자리는 만들지 않는다.
 */
export function HomePage() {
  const navigate = useNavigate()
  const categories = useCatalog('store-categories')
  const categoryNames = toDisplayNameMap(categories.data)

  function submit(filters: StoreSearchFilters) {
    // 홈에서 제출한 조건이 결과 목록으로 그대로 이어진다.
    void navigate(`/stores?${writeFilters(filters).toString()}`)
  }

  return (
    <div className="mi-container home">
      <section className="home__hero">
        <p className="home__eyebrow">DISCOVER LOCAL FLAVORS</p>
        <h1 className="home__title">
          맛있는 기다림, <strong>미리냠</strong>과 함께 시작하세요.
        </h1>
        <p className="home__lead">
          예약부터 메뉴 미리 선택, 픽업까지. 줄 서지 않고 여유롭게 맛집을 즐겨
          보세요.
        </p>

        <SearchFilterForm
          layout="compact"
          value={EMPTY_FILTERS}
          storeCategories={categories.data ?? []}
          onSubmit={submit}
        />
      </section>

      <section className="home__categories" aria-label="카테고리로 찾기">
        <h2>어떤 메뉴를 찾으시나요?</h2>
        {categories.isPending && <p>카테고리를 불러오는 중입니다.</p>}
        {categories.isSuccess && categories.data.length > 0 && (
          <ul className="home__category-list">
            {categories.data.map((item) => (
              <li key={item.code}>
                <button
                  type="button"
                  className="mi-chip"
                  onClick={() =>
                    submit({ ...EMPTY_FILTERS, storeCategoryCode: item.code })
                  }
                >
                  {/* 표시명은 서버 catalog가 소유한다. code로 이름을 만들지 않는다. */}
                  {categoryNames.get(item.code) ?? item.displayName}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
