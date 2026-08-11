import type { CSSProperties } from 'react'
import { Link } from 'react-router'
import { Badge } from '../../../shared/ui/Badge'
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
 * 시안의 media 자리에는 카테고리 일러스트를 쓴다. 1차 MVP 계약에 매장 이미지
 * 필드가 없어 매장 사진은 쓸 수 없고, 대신 이미 번들에 있는 카테고리 일러스트를
 * 둔다. 평면 일러스트라 실제 매장 사진으로 오인될 여지가 적고, 어떤 카테고리인지
 * 아래 메타 줄이 문구로 함께 알린다.
 */
export function StoreCard({ store, categoryNames, detailSearch }: Props) {
  const availability = store.reservationAvailability
  const art = categoryArt(store.storeCategoryCode)
  const tint = categoryTint(store.storeCategoryCode)

  return (
    <li className="mi-card mi-card--interactive store-card">
      <div
        className="store-card__media"
        style={{ '--tile-from': tint.from, '--tile-to': tint.to } as CSSProperties}
      >
        {art !== null && (
          <img
            className="store-card__art"
            src={art}
            // 매장 사진이 아니라 카테고리 장식이다. 카테고리명은 본문에 있다.
            alt=""
            width={320}
            height={320}
            loading="lazy"
            decoding="async"
          />
        )}
        <div className="store-card__badges">
          <Badge tone={OPERATION_STATUS_TONE[store.operationStatus]}>
            {OPERATION_STATUS_LABEL[store.operationStatus]}
          </Badge>
          <Badge tone={AVAILABILITY_TONE[availability]}>
            {AVAILABILITY_LABEL[availability]}
          </Badge>
        </div>
      </div>

      <div className="mi-card__body">
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
