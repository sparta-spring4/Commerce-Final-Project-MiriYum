import { PUBLIC_PATHS } from '../../../../app/routes/paths/publicPaths'
import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import type { CSSProperties } from 'react'
import { Link, useNavigate } from 'react-router'
import { Loading } from '../../../../shared/ui/Feedback'
import { toDisplayNameMap, useCatalog, useStoreSearch } from '../api/queries'
import { categoryArt, categoryTint } from '../model/categoryArt'
import { REGION_LABEL } from '../model/labels'
import { regionArt } from '../model/regionArt'
import {
  EMPTY_FILTERS,
  REGIONS,
  writeFilters,
  type CatalogItem,
  type Region,
  type StoreSearchFilters,
} from '../model/searchParams'
import { SearchFilterForm } from './SearchFilterForm'
import { CarouselNav, CarouselTrack, useCarousel } from './StoreCarousel'
import { StoreCard } from './StoreCard'

/** 히어로 배경 띠에 쓸 일러스트 순서. 순수 장식이다. */
const HERO_STRIP = [
  'KOREAN',
  'JAPANESE',
  'WESTERN',
  'ASIAN',
  'CHINESE',
  'CAFE_BAKERY',
  'BAR',
  'ETC',
]

/**
 * 홈 캐러셀에 담을 매장 수.
 *
 * 시안은 한 화면에 3장을 보여 주고 좌우 버튼으로 넘긴다. 넘길 것이 있어야
 * 버튼이 의미가 있으므로 세 화면 분량을 받아 둔다.
 */
const PREVIEW_SIZE = 9

/**
 * 홈. 시안 `_5`의 섹션 구성을 따른다.
 *
 * 히어로 → 지역 → 카테고리 → 매장 둘러보기 → 특징 → 시작 CTA.
 *
 * 사진 자산이 필요한 자리에는 번들의 카테고리 일러스트를 쓴다. 1차 MVP 계약에
 * 매장 이미지 필드가 없어 실제 매장 사진은 존재하지 않는다.
 */
export function HomePage() {
  const navigate = useNavigate()
  const categories = useCatalog('store-categories')
  const categoryNames = toDisplayNameMap(categories.data)

  /*
   * 시안의 "오늘의 추천 맛집" 자리.
   *
   * 1차 MVP 계약에 큐레이션·추천 기준이 없으므로 추천으로 표시하지 않는다.
   * 계약이 허용하는 정렬(createdAt,desc)로 최근 등록된 매장을 보여 주고
   * 제목도 그대로 "새로 들어온 매장"이라고 쓴다.
   */
  const preview = useStoreSearch({
    size: PREVIEW_SIZE,
    sort: 'createdAt,desc',
  })

  const carousel = useCarousel(preview.data?.items.length ?? 0)

  function submit(filters: StoreSearchFilters) {
    // 홈에서 제출한 조건이 결과 목록으로 그대로 이어진다.
    void navigate(`/stores?${writeFilters(filters).toString()}`)
  }

  return (
    <div className="home">
      <div className="home__ambient" aria-hidden="true" />

      <section className="home__hero">
        <div className="home__hero-media" aria-hidden="true">
          <div className="home__hero-strip">
            {HERO_STRIP.map((code) => {
              const art = categoryArt(code)
              return art === null ? null : (
                <img key={code} src={art} alt="" width={320} height={320} />
              )
            })}
          </div>
        </div>

        <div className="mi-container home__hero-inner">
          <div className="home__hero-copy">
            <p className="home__eyebrow">Discover Local Flavors</p>
            <h1 className="home__title">
              맛있는 기다림, <strong>미리냠</strong>과 함께 시작하세요.
            </h1>
            <p className="home__lead">
              예약부터 메뉴 미리 선택, 픽업까지. 줄 서지 않고 여유롭게 맛집의
              즐거움을 누려 보세요.
            </p>
          </div>

          <SearchFilterForm
            layout="compact"
            value={EMPTY_FILTERS}
            storeCategories={categories.data ?? []}
            onSubmit={submit}
          />
        </div>
      </section>

      <div className="mi-container">
        <section className="home__section" aria-label="지역으로 찾기">
          <div className="home__section-head">
            <div>
              <h2>어디로 가시나요?</h2>
              <p>지역을 고르면 그 지역의 매장을 찾습니다.</p>
            </div>
          </div>

          {/*
            시안은 동 단위 8칸이지만 계약의 Region은 광역 5개다.
            시/구/동 세분화는 2차 MVP #117 범위다(Epic #190 지역 단위 결정).
          */}
          <div className="home__region-grid">
            {REGIONS.map((region) => (
              <RegionTile
                key={region}
                region={region}
                onSelect={() => submit({ ...EMPTY_FILTERS, region })}
              />
            ))}
          </div>
        </section>

        <section className="home__section" aria-label="카테고리로 찾기">
          <div className="home__section-head">
            <div>
              <h2>무엇을 드시고 싶으신가요?</h2>
              <p>다양한 카테고리의 맛집을 탐색해 보세요.</p>
            </div>
            <Link className="home__section-more" to={PUBLIC_PATHS.stores}>
              전체보기 <span aria-hidden="true">→</span>
            </Link>
          </div>

          {categories.isPending && <Loading label="카테고리를 불러오는 중입니다." />}

          {categories.isSuccess && categories.data.length > 0 && (
            <div className="home__category-grid">
              {categories.data.map((item) => (
                <CategoryCard
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

        <section className="home__section" aria-label="새로 들어온 매장">
          <div className="home__section-head">
            <div>
              <h2>새로 들어온 매장</h2>
              <p>가장 최근에 등록된 매장입니다.</p>
            </div>
            {preview.isSuccess && preview.data.items.length > 0 && (
              <CarouselNav
                label="새로 들어온 매장"
                atStart={carousel.atStart}
                atEnd={carousel.atEnd}
                onScroll={carousel.scrollByPage}
              />
            )}
          </div>

          {preview.isPending && <Loading label="매장을 불러오는 중입니다." />}

          {preview.isSuccess && preview.data.items.length > 0 && (
            <CarouselTrack
              label="새로 들어온 매장 목록"
              trackRef={carousel.trackRef}
              onScroll={carousel.sync}
            >
              {preview.data.items.map((store) => (
                <StoreCard
                  key={store.storeId}
                  store={store}
                  categoryNames={categoryNames}
                  detailSearch=""
                />
              ))}
            </CarouselTrack>
          )}

          {preview.isSuccess && preview.data.items.length === 0 && (
            <p>아직 등록된 매장이 없습니다.</p>
          )}
        </section>

        <section className="home__section" aria-label="미리냠이 특별한 이유">
          <div className="home__section-head">
            <div>
              <h2>미리냠이 특별한 이유</h2>
              <p>당신의 미식 경험을 한층 더 완벽하게 만들어 줄 기능들</p>
            </div>
          </div>

          {/*
            시안 구조: col-span-8 큰 카드 + col-span-4 카드 둘.
            내용은 1차 MVP에서 실제 동작하는 세 거래로 채운다. 시안의
            "실시간 스마트 웨이팅"·"검증된 리얼 리뷰"는 고도화라 문구로도
            노출하지 않는다.
          */}
          <div className="home__features">
            <article className="home__feature home__feature--lead">
              <div className="home__feature-body">
                <span className="home__feature-mark" aria-hidden="true">
                  1
                </span>
                <h3>
                  메뉴 미리 선택으로
                  <br />
                  도착 즉시 즐기세요
                </h3>
                <p>
                  예약과 동시에 대표 메뉴를 선택할 수 있습니다. 매장에 도착하면
                  기다림 없이 준비된 음식을 맛보세요.
                </p>
                <Link className="home__feature-more" to={PUBLIC_PATHS.stores}>
                  매장 둘러보기 <span aria-hidden="true">→</span>
                </Link>
              </div>

              <FeatureInset />
            </article>

            <div className="home__feature-column">
              <article className="home__feature">
                <span className="home__feature-mark" aria-hidden="true">
                  2
                </span>
                <h3>조건에 맞는 자리를 먼저 확인</h3>
                <p>
                  날짜·시간·인원을 넣으면 그 조건으로 예약할 수 있는 매장만 골라
                  볼 수 있습니다.
                </p>
              </article>

              <article className="home__feature home__feature--accent">
                <span className="home__feature-mark" aria-hidden="true">
                  3
                </span>
                <h3>기다리지 않는 픽업 예약</h3>
                <p>
                  원하는 픽업 시간대를 고르고 메뉴를 주문한 뒤, 그 시간에 맞춰
                  찾아가기만 하면 됩니다.
                </p>
              </article>
            </div>
          </div>
        </section>

        {/*
          시안 자리는 앱 다운로드 배너다. 앱이 없으므로 없는 스토어 링크와
          쿠폰 문구를 만들지 않고, 같은 형태에 실제로 갈 수 있는 다음 행동을 둔다.
        */}
        <section className="home__cta" aria-label="지금 시작하기">
          <div className="home__cta-body">
            <h2>
              미리냠으로 더 빠르고
              <br />
              편리하게 예약하세요
            </h2>
            <p>매장을 찾아보고, 계정을 만들면 바로 예약할 수 있습니다.</p>
            <div className="home__cta-actions">
              <Link className="mi-button" to={PUBLIC_PATHS.stores}>
                매장 찾기
              </Link>
              <Link className="mi-button" to={CONSUMER_PATHS.signUp}>
                회원가입
              </Link>
            </div>
          </div>

          <CtaMock />
        </section>
      </div>
    </div>
  )
}

/**
 * 지역 타일.
 *
 * 시안처럼 정사각 이미지 아래에 지역명을 둔다. 랜드마크 일러스트는 프론트
 * 번들의 정적 자산이고, 이름은 이미지가 아니라 아래 텍스트가 전달한다.
 */
function RegionTile({
  region,
  onSelect,
}: {
  region: Region
  onSelect: () => void
}) {
  const tint = categoryTint(region)
  const label = REGION_LABEL[region]

  return (
    <button type="button" className="home__region" onClick={onSelect}>
      <span
        className="home__region-tile"
        style={{ '--tile-from': tint.from, '--tile-to': tint.to } as CSSProperties}
      >
        <img
          className="home__region-art"
          src={regionArt(region)}
          // 바로 아래에 같은 이름이 있다. alt를 채우면 두 번 읽힌다.
          alt=""
          width={480}
          height={480}
          loading="lazy"
          decoding="async"
        />
      </span>
      <span className="home__region-name">{label}</span>
    </button>
  )
}

/**
 * 카테고리 카드.
 *
 * 시안 구조 그대로: 이미지 위에 하단 그라디언트를 덮고 좌하단에 이름과
 * 원형 화살표를 얹는다. 표시명은 서버 catalog 값을 쓴다.
 */
function CategoryCard({
  item,
  onSelect,
}: {
  item: CatalogItem
  onSelect: () => void
}) {
  const art = categoryArt(item.code)
  const tint = categoryTint(item.code)

  return (
    <button
      type="button"
      className="home__category"
      style={{ '--tile-from': tint.from, '--tile-to': tint.to } as CSSProperties}
      onClick={onSelect}
    >
      {art !== null && (
        <img
          className="home__category-art"
          src={art}
          alt=""
          width={320}
          height={320}
          loading="lazy"
          decoding="async"
        />
      )}
      <span className="home__category-scrim" aria-hidden="true" />
      <span className="home__category-foot">
        <span className="home__category-name">{item.displayName}</span>
        <span className="home__category-go" aria-hidden="true">
          →
        </span>
      </span>
    </button>
  )
}

/**
 * 큰 카드의 인셋 그래픽.
 *
 * 시안은 여기에 "75% 조리 준비 중" 진행 링을 뒀다. 1차 MVP에 조리 상태 계약이
 * 없어 가짜 진행 상태가 되므로 만들지 않고, 실제 자산인 일러스트로 채운다.
 */
function FeatureInset() {
  const art = categoryArt('WESTERN')

  return (
    <div className="home__feature-inset" aria-hidden="true">
      <div className="home__feature-inset-head">
        {art !== null && <img src={art} alt="" width={320} height={320} />}
        <div>
          <p className="home__feature-inset-title">대표 메뉴 미리 선택</p>
          <p className="home__feature-inset-sub">예약과 함께 전달</p>
        </div>
      </div>

      <div className="home__feature-fan">
        {['KOREAN', 'JAPANESE', 'CAFE_BAKERY'].map((code) => {
          const tile = categoryArt(code)
          return tile === null ? null : (
            <img key={code} src={tile} alt="" width={320} height={320} loading="lazy" />
          )
        })}
      </div>
    </div>
  )
}

/** CTA 배너의 폰 목업. 순수 장식이라 보조기술에서 숨긴다. */
function CtaMock() {
  const art = categoryArt('KOREAN')

  return (
    <div className="home__cta-mock" aria-hidden="true">
      <div className="home__cta-mock-screen">
        <div className="home__cta-mock-hero">
          {art !== null && <img src={art} alt="" width={320} height={320} loading="lazy" />}
        </div>
        <span className="home__cta-mock-bar" />
        <span className="home__cta-mock-bar home__cta-mock-bar--short" />
        <div className="home__cta-mock-grid">
          <span />
          <span />
          <span />
          <span />
        </div>
        <div className="home__cta-mock-button" />
      </div>
    </div>
  )
}
