import { useState, type CSSProperties } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { hasErrorCode } from '../../../../shared/api/apiError'
import { Badge } from '../../../../shared/ui/Badge'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Icon, type IconName } from '../../../../shared/ui/Icon'
import {
  toDisplayNameMap,
  useCatalog,
  useStoreDetail,
  useStoreImages,
  useStoreMenus,
} from '../api/queries'
import { categoryArt, categoryTint } from '../model/categoryArt'
import {
  AVAILABILITY_DESCRIPTION,
  AVAILABILITY_LABEL,
  AVAILABILITY_TONE,
  OPERATION_STATUS_LABEL,
  OPERATION_STATUS_TONE,
  REGION_LABEL,
  catalogLabel,
} from '../model/labels'
import {
  readFilters,
  reservationConditionState,
  toDetailQuery,
  writeFilters,
  type StoreSearchFilters,
} from '../model/searchParams'
import { MenuList } from './MenuList'
import { StoreTransactionActions } from './StoreTransactionActions'
import { OperatingHoursTable, ReservationTimeSlotsTable } from './WeeklySchedule'

/** 매장을 찾을 수 없을 때 서버가 주는 코드. */
const STORE_NOT_FOUND = 'STORE_001'

/**
 * 매장 상세 화면.
 *
 * 검색에서 넘어온 예약 조건을 그대로 이어받아 같은 조건의 가용성을 보여 준다.
 * 조건이 불완전하면 가용성을 요청하지 않고 `NOT_REQUESTED`로 표시한다.
 */
export function StoreDetailPage() {
  const { storeId = '' } = useParams()
  const [searchParams] = useSearchParams()
  const filters = readFilters(searchParams)

  const detail = useStoreDetail(storeId, toDetailQuery(filters))
  const images = useStoreImages(storeId)
  const menus = useStoreMenus(storeId)
  const storeCategories = useCatalog('store-categories')
  const storeTags = useCatalog('store-tags')
  const menuCategories = useCatalog('menu-categories')
  const [failedPrimaryImageUrl, setFailedPrimaryImageUrl] = useState<string | null>(null)

  const backToSearch = `/stores?${writeFilters(filters).toString()}`

  if (detail.isPending) {
    return (
      <div className="mi-container store-detail">
        <Loading label="매장 정보를 불러오는 중입니다." />
      </div>
    )
  }

  if (detail.isError) {
    const notFound = hasErrorCode(detail.error, STORE_NOT_FOUND)
    return (
      <div className="mi-container store-detail">
        <ErrorState
          error={detail.error}
          message={
            notFound
              ? '찾을 수 없는 매장입니다. 목록에서 다시 선택해 주세요.'
              : undefined
          }
          onRetry={notFound ? undefined : () => void detail.refetch()}
        />
        <p>
          <Link to={backToSearch}>매장 찾기로 돌아가기</Link>
        </p>
      </div>
    )
  }

  const store = detail.data
  const categoryNames = toDisplayNameMap(storeCategories.data)
  const tagNames = toDisplayNameMap(storeTags.data)
  const menuCategoryNames = toDisplayNameMap(menuCategories.data)
  const availability = store.reservationAvailability
  const conditionState = reservationConditionState(filters)

  const art = categoryArt(store.storeCategoryCode)
  const tint = categoryTint(store.storeCategoryCode)
  const primaryImageUrl = images.data?.[0]?.url
  const canDisplayPrimaryImage = primaryImageUrl !== undefined
    && primaryImageUrl !== failedPrimaryImageUrl

  return (
    <div className="store-detail">
      {/* 공개 이미지가 없거나 조회에 실패하면 카테고리 일러스트를 유지한다. */}
      <section
        className="store-detail__hero"
        style={{ '--tile-from': tint.from, '--tile-to': tint.to } as CSSProperties}
      >
        {canDisplayPrimaryImage ? (
          <img
            className="store-detail__hero-art"
            src={primaryImageUrl}
            alt="매장 대표 이미지"
            width={320}
            height={320}
            decoding="async"
            onError={() => setFailedPrimaryImageUrl(primaryImageUrl)}
          />
        ) : art !== null && (
          <img
            className="store-detail__hero-art"
            src={art}
            alt=""
            width={320}
            height={320}
            decoding="async"
          />
        )}
        <span className="store-detail__hero-scrim" aria-hidden="true" />

        <div className="mi-container store-detail__hero-inner">
          <nav aria-label="이동 경로" className="store-detail__breadcrumb">
            <Link to={backToSearch}>
              <Icon name="arrowLeft" className="mi-icon--sm" />
              매장 찾기
            </Link>
          </nav>

          <div className="store-detail__badges">
            <Badge tone={OPERATION_STATUS_TONE[store.operationStatus]}>
              {OPERATION_STATUS_LABEL[store.operationStatus]}
            </Badge>
            <span className="store-detail__category">
              {catalogLabel(store.storeCategoryCode, categoryNames)}
            </span>
          </div>

          <h1>{store.name}</h1>

          <p className="store-detail__address">
            <Icon name="pin" />
            {`${REGION_LABEL[store.region]} · ${store.address}`}
          </p>

          {store.tags.length > 0 && (
            <ul className="store-detail__tags">
              {store.tags.map((tag) => (
                <li key={tag} className="mi-tag">
                  {catalogLabel(tag, tagNames)}
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>

      <div className="mi-container store-detail__layout">
        <div className="store-detail__main">
          {store.description.length > 0 && (
            <section
              className="mi-card mi-card--roomy store-detail__section"
              aria-label="매장 소개"
            >
              <div className="mi-card__body mi-card__body--roomy">
                <h2>매장 정보</h2>
                <p className="store-detail__description">{store.description}</p>
              </div>
            </section>
          )}

          <section
            className="mi-card mi-card--roomy store-detail__section"
            aria-label="영업시간"
          >
            <div className="mi-card__body mi-card__body--roomy">
              {/* 시안은 정보 항목마다 원형 아이콘 타일을 앞에 둔다. */}
              <div className="store-detail__tile">
                <span className="store-detail__tile-mark" aria-hidden="true">
                  <Icon name="clock" />
                </span>
                <div>
                  <h2>영업시간</h2>
                  <p className="store-detail__timezone">{`기준 시간대: ${store.timeZoneId}`}</p>
                </div>
              </div>
              <OperatingHoursTable days={store.operatingHours} />
            </div>
          </section>

          <section
            className="mi-card mi-card--roomy store-detail__section"
            aria-label="예약 접수 시간대"
          >
            <div className="mi-card__body mi-card__body--roomy">
              <div className="store-detail__tile">
                <span className="store-detail__tile-mark" aria-hidden="true">
                  <Icon name="calendar" />
                </span>
                <div>
                  <h2>예약 접수 시간대</h2>
                </div>
              </div>
              <ReservationTimeSlotsTable days={store.reservationTimeSlots} />
            </div>
          </section>

          <section className="store-detail__section" aria-label="대표 메뉴">
            <h2 className="store-detail__section-title">대표 메뉴</h2>
            <MenuList
              menus={store.representativeMenus}
              menuCategoryNames={menuCategoryNames}
              emptyTitle="등록된 대표 메뉴가 없습니다."
            />
          </section>

          <section className="store-detail__section" aria-label="전체 메뉴">
            <h2 className="store-detail__section-title">전체 메뉴</h2>
            {menus.isPending && <Loading label="메뉴를 불러오는 중입니다." />}
            {menus.isError && (
              <ErrorState
                error={menus.error}
                message="메뉴를 불러오지 못했습니다."
                onRetry={() => void menus.refetch()}
              />
            )}
            {menus.isSuccess && (
              <MenuList
                menus={menus.data}
                menuCategoryNames={menuCategoryNames}
                emptyTitle="공개된 메뉴가 없습니다."
              />
            )}
          </section>
        </div>

        {/*
          시안의 오른쪽 고정 예약 패널.
          날짜·시간·인원 위젯 자리에는 검색에서 이어받은 조건을 그대로 보여 준다.
          여기서 값을 새로 고르게 하면 URL의 조건과 두 벌이 되어 어긋난다.
        */}
        <aside className="store-detail__panel" aria-label="예약 진행하기">
          <div className="mi-card mi-card--roomy store-detail__panel-card">
            <div className="mi-card__body">
              <h2 className="store-detail__panel-title">예약 진행하기</h2>

              <ConditionSummary filters={filters} />

              <p
                className={`store-detail__availability store-detail__availability--${availability.toLowerCase()}`}
              >
                <Badge tone={AVAILABILITY_TONE[availability]}>
                  {AVAILABILITY_LABEL[availability]}
                </Badge>
                <span>{AVAILABILITY_DESCRIPTION[availability]}</span>
              </p>

              {conditionState !== 'complete' && (
                <Link className="store-detail__panel-link" to={backToSearch}>
                  조건 바꾸러 가기
                  <Icon name="arrowRight" className="mi-icon--sm" />
                </Link>
              )}

              {conditionState === 'complete' && availability === 'AVAILABLE' && (
                <Alert tone="info" title="지금 보이는 가용성은 확정이 아닙니다.">
                  <p>
                    예약을 만드는 시점에 서버가 수용량과 메뉴 수량을 다시
                    확인합니다.
                  </p>
                </Alert>
              )}

              <StoreTransactionActions
                store={store}
                search={writeFilters(filters).toString()}
              />
            </div>
          </div>
        </aside>
      </div>
    </div>
  )
}

/**
 * 패널 위쪽의 조건 요약.
 *
 * 검색에서 넘어온 값만 보여 준다. 입력하지 않은 조건은 빈 줄을 만들지 않고
 * 한 줄로 "조건을 넣지 않았다"고 밝힌다.
 */
function ConditionSummary({ filters }: { filters: StoreSearchFilters }) {
  const rows: { key: string; icon: IconName; text: string }[] = []

  if (filters.serviceDate.length > 0) {
    rows.push({ key: 'date', icon: 'calendar', text: filters.serviceDate })
  }
  if (filters.startTime.length > 0) {
    rows.push({ key: 'time', icon: 'clock', text: filters.startTime })
  }
  if (filters.partySize.length > 0) {
    rows.push({ key: 'party', icon: 'group', text: `${filters.partySize}명` })
  }

  if (rows.length === 0) {
    return (
      <p className="store-detail__panel-empty">
        검색에서 넘어온 예약 조건이 없습니다.
      </p>
    )
  }

  return (
    <ul className="store-detail__panel-conditions">
      {rows.map((row) => (
        <li key={row.key}>
          <Icon name={row.icon} />
          <span>{row.text}</span>
        </li>
      ))}
    </ul>
  )
}
