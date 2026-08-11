import { useNavigate } from 'react-router'
import { useCatalog } from '../api/queries'
import { categoryArt, categoryTint } from '../model/categoryArt'
import {
  EMPTY_FILTERS,
  writeFilters,
  type CatalogItem,
  type StoreSearchFilters,
} from '../model/searchParams'
import { SearchFilterForm } from './SearchFilterForm'

/**
 * 홈. 검색 진입점이다.
 *
 * 큐레이션·추천 목록은 두지 않는다. 1차 MVP 계약에 큐레이션 기준이 없고
 * 이력 기반 추천은 2차 MVP다. 시안의 "오늘의 추천 맛집" 카드 자리는 만들지 않는다.
 *
 * 반대로 사진이 없다는 이유로 시안의 시각 언어까지 버리지는 않는다.
 * 히어로 레이어·앰비언트 광원·카테고리 타일은 데이터가 아니라 표현이다.
 */
export function HomePage() {
  const navigate = useNavigate()
  const categories = useCatalog('store-categories')

  function submit(filters: StoreSearchFilters) {
    // 홈에서 제출한 조건이 결과 목록으로 그대로 이어진다.
    void navigate(`/stores?${writeFilters(filters).toString()}`)
  }

  return (
    <div className="home">
      <div className="home__ambient" aria-hidden="true" />

      <div className="mi-container">
        <section className="home__hero">
          <p className="home__eyebrow">Discover Local Flavors</p>
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

        <section className="home__section" aria-label="카테고리로 찾기">
          <div className="home__section-head">
            <h2>어떤 메뉴를 찾으시나요?</h2>
            <p>카테고리를 고르면 그 조건으로 매장을 찾습니다.</p>
          </div>

          {categories.isPending && <p>카테고리를 불러오는 중입니다.</p>}

          {categories.isSuccess && categories.data.length > 0 && (
            <div className="home__category-grid">
              {categories.data.map((item) => (
                <CategoryTile
                  key={item.code}
                  item={item}
                  onSelect={() =>
                    submit({ ...EMPTY_FILTERS, storeCategoryCode: item.code })
                  }
                />
              ))}
            </div>
          )}
        </section>

        <section className="home__section" aria-label="미리냠이 특별한 이유">
          <div className="home__section-head">
            <h2>미리냠이 특별한 이유</h2>
            <p>지금 바로 쓸 수 있는 기능만 담았습니다.</p>
          </div>

          {/*
            1차 MVP에서 실제로 동작하는 세 거래만 소개한다.
            시안의 "실시간 스마트 웨이팅"·"검증된 리얼 리뷰" 카드는 고도화라
            문구로도 노출하지 않는다.
          */}
          <div className="home__features">
            <Feature
              mark="1"
              title="조건에 맞는 자리를 먼저 확인"
              description="날짜·시간·인원을 넣으면 예약 가능한 매장만 골라 볼 수 있습니다."
            />
            <Feature
              mark="2"
              title="메뉴를 미리 선택하고 방문"
              description="예약과 동시에 대표 메뉴를 골라 두면 도착 시간에 맞춰 준비합니다."
            />
            <Feature
              mark="3"
              title="기다리지 않는 픽업 예약"
              description="원하는 시간대를 골라 메뉴를 주문하고 찾아가기만 하면 됩니다."
            />
          </div>
        </section>
      </div>
    </div>
  )
}

/**
 * 카테고리 타일.
 *
 * 일러스트는 프론트 번들의 정적 자산이고 catalog code로 고른다. 매핑에 없는
 * 코드는 그라디언트 타일로 떨어진다. 표시명은 언제나 서버 값을 쓴다.
 */
function CategoryTile({
  item,
  onSelect,
}: {
  item: CatalogItem
  onSelect: () => void
}) {
  const art = categoryArt(item.code)
  const tint = categoryTint(item.code)

  return (
    <button type="button" className="home__category" onClick={onSelect}>
      <span
        className="home__category-tile"
        style={{ '--tile-from': tint.from, '--tile-to': tint.to } as React.CSSProperties}
      >
        {art === null ? (
          // 일러스트가 없는 코드. 첫 글자는 장식이고 의미는 아래 이름이 전한다.
          <span aria-hidden="true">{[...item.displayName][0]}</span>
        ) : (
          <img
            className="home__category-art"
            src={art}
            // 바로 아래에 같은 이름이 있다. alt를 채우면 두 번 읽힌다.
            alt=""
            width={320}
            height={320}
            loading="lazy"
            decoding="async"
          />
        )}
      </span>
      <span className="home__category-name">{item.displayName}</span>
    </button>
  )
}

function Feature({
  mark,
  title,
  description,
}: {
  mark: string
  title: string
  description: string
}) {
  return (
    <article className="home__feature">
      {/* span으로 둔다. p로 두면 아래 본문 문단 규칙이 색·크기를 덮어쓴다. */}
      <span className="home__feature-mark" aria-hidden="true">
        {mark}
      </span>
      <h3>{title}</h3>
      <p>{description}</p>
    </article>
  )
}
