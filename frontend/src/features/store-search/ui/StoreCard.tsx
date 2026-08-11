import { Link } from 'react-router'
import { Badge } from '../../../shared/ui/Badge'
import {
  AVAILABILITY_DESCRIPTION,
  AVAILABILITY_LABEL,
  AVAILABILITY_TONE,
  OPERATION_STATUS_LABEL,
  OPERATION_STATUS_TONE,
  REGION_LABEL,
  catalogLabel,
} from '../model/labels'
import type { StoreSummary } from '../model/searchParams'

interface Props {
  store: StoreSummary
  categoryNames: ReadonlyMap<string, string>
  /** 상세로 넘길 때 현재 검색 조건을 유지한다. */
  detailSearch: string
}

/**
 * 검색 결과 항목.
 *
 * 1차 MVP 계약에 이미지·평점·리뷰 수 필드가 없다. 시안의 사진·별점 자리는
 * 만들지 않고 계약이 주는 값만 표시한다.
 */
export function StoreCard({ store, categoryNames, detailSearch }: Props) {
  const availability = store.reservationAvailability

  return (
    <li className="mi-card mi-card--interactive store-card">
      <div className="mi-card__body">
        <div className="store-card__badges">
          <Badge tone={OPERATION_STATUS_TONE[store.operationStatus]}>
            {OPERATION_STATUS_LABEL[store.operationStatus]}
          </Badge>
          <Badge tone={AVAILABILITY_TONE[availability]}>
            {AVAILABILITY_LABEL[availability]}
          </Badge>
        </div>

        <h3 className="store-card__name">
          <Link to={{ pathname: `/stores/${store.storeId}`, search: detailSearch }}>
            {store.name}
          </Link>
        </h3>

        <p className="store-card__meta">
          {`${REGION_LABEL[store.region]} · ${catalogLabel(store.storeCategoryCode, categoryNames)}`}
        </p>
        <p className="store-card__address">{store.address}</p>

        {/* 3분기를 뱃지 색뿐 아니라 문장으로도 구분한다. */}
        <p className="store-card__availability">
          {AVAILABILITY_DESCRIPTION[availability]}
        </p>

        <StoreModeList modes={store.modes} />
      </div>
    </li>
  )
}

/**
 * 매장이 활성화한 거래 방식만 나열한다.
 * 꺼진 방식은 비활성 버튼으로 남기지 않고 아예 표시하지 않는다.
 */
function StoreModeList({ modes }: { modes: StoreSummary['modes'] }) {
  const enabled = [
    modes.reservationEnabled ? '예약' : null,
    modes.menuHoldEnabled ? '메뉴 미리 선택' : null,
    modes.pickupEnabled ? '픽업' : null,
  ].filter((label): label is string => label !== null)

  if (enabled.length === 0) {
    return null
  }

  return (
    <p className="store-card__modes">
      <span className="visually-hidden">이용 가능한 방식: </span>
      {enabled.join(' · ')}
    </p>
  )
}
