import { Link, useParams, useSearchParams } from 'react-router'
import { hasErrorCode } from '../../../shared/api/apiError'
import { Badge } from '../../../shared/ui/Badge'
import { Alert, ErrorState, Loading } from '../../../shared/ui/Feedback'
import {
  toDisplayNameMap,
  useCatalog,
  useStoreDetail,
  useStoreMenus,
} from '../api/queries'
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
  const menus = useStoreMenus(storeId)
  const storeCategories = useCatalog('store-categories')
  const storeTags = useCatalog('store-tags')
  const menuCategories = useCatalog('menu-categories')

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

  return (
    <div className="mi-container store-detail">
      <nav aria-label="이동 경로" className="store-detail__breadcrumb">
        <Link to={backToSearch}>매장 찾기</Link>
        <span aria-hidden="true"> / </span>
        <span>{store.name}</span>
      </nav>

      <header className="store-detail__header">
        <div className="store-detail__badges">
          <Badge tone={OPERATION_STATUS_TONE[store.operationStatus]}>
            {OPERATION_STATUS_LABEL[store.operationStatus]}
          </Badge>
          <Badge tone={AVAILABILITY_TONE[availability]}>
            {AVAILABILITY_LABEL[availability]}
          </Badge>
        </div>
        <h1>{store.name}</h1>
        <p className="store-detail__meta">
          {`${REGION_LABEL[store.region]} · ${catalogLabel(store.storeCategoryCode, categoryNames)}`}
        </p>
        <p className="store-detail__address">{store.address}</p>
        {store.tags.length > 0 && (
          <ul className="store-detail__tags">
            {store.tags.map((tag) => (
              <li key={tag} className="mi-badge mi-badge--neutral">
                {catalogLabel(tag, tagNames)}
              </li>
            ))}
          </ul>
        )}
      </header>

      <section className="store-detail__availability" aria-label="예약 가능 여부">
        <p>{AVAILABILITY_DESCRIPTION[availability]}</p>
        {conditionState === 'complete' && availability === 'AVAILABLE' && (
          <Alert tone="info" title="지금 보이는 가용성은 확정이 아닙니다.">
            <p>
              예약을 만드는 시점에 서버가 수용량과 메뉴 수량을 다시 확인합니다.
            </p>
          </Alert>
        )}
        <StoreTransactionActions
          store={store}
          search={writeFilters(filters).toString()}
        />
      </section>

      {store.description.length > 0 && (
        <section className="mi-card store-detail__section" aria-label="매장 소개">
          <div className="mi-card__body">
            <h2>매장 정보</h2>
            <p>{store.description}</p>
          </div>
        </section>
      )}

      <section className="mi-card store-detail__section" aria-label="영업시간">
        <div className="mi-card__body">
          <h2>영업시간</h2>
          <p className="store-detail__timezone">{`기준 시간대: ${store.timeZoneId}`}</p>
          <OperatingHoursTable days={store.operatingHours} />
        </div>
      </section>

      <section
        className="mi-card store-detail__section"
        aria-label="예약 접수 시간대"
      >
        <div className="mi-card__body">
          <h2>예약 접수 시간대</h2>
          <ReservationTimeSlotsTable days={store.reservationTimeSlots} />
        </div>
      </section>

      <section className="store-detail__section" aria-label="대표 메뉴">
        <h2>대표 메뉴</h2>
        <MenuList
          menus={store.representativeMenus}
          menuCategoryNames={menuCategoryNames}
          emptyTitle="등록된 대표 메뉴가 없습니다."
        />
      </section>

      <section className="store-detail__section" aria-label="전체 메뉴">
        <h2>전체 메뉴</h2>
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
  )
}
