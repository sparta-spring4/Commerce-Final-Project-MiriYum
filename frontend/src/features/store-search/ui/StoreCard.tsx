import type { CSSProperties } from 'react'
import { Link } from 'react-router'
import { Badge } from '../../../shared/ui/Badge'
import { Icon, type IconName } from '../../../shared/ui/Icon'
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
import type { ReservationAvailability, StoreSummary } from '../model/searchParams'

interface Props {
  store: StoreSummary
  categoryNames: ReadonlyMap<string, string>
  /** 상세로 넘길 때 현재 검색 조건을 유지한다. */
  detailSearch: string
}

/**
 * 가용성 한 줄에 붙는 아이콘.
 *
 * 아이콘은 문구를 보강할 뿐이다. 세 상태의 뜻은 언제나 옆의 문장이 전달한다.
 */
const AVAILABILITY_ICON: Record<ReservationAvailability, IconName> = {
  NOT_REQUESTED: 'info',
  AVAILABLE: 'checkCircle',
  UNAVAILABLE: 'alert',
}

/**
 * 검색 결과 항목.
 *
 * 시안 `_4`의 카드 구성을 따른다. media → 이름·지역·카테고리 → 이용 방식 태그
 * → 가용성 한 줄 → 다음 행동 버튼.
 *
 * media 자리에는 카테고리 일러스트를 쓴다. 1차 MVP 계약에 매장 이미지 필드가
 * 없어 매장 사진은 쓸 수 없고, 대신 이미 번들에 있는 카테고리 일러스트를 둔다.
 * 평면 일러스트라 실제 매장 사진으로 오인될 여지가 적고, 어떤 카테고리인지
 * 아래 메타 줄이 문구로 함께 알린다.
 *
 * 시안의 별점·리뷰 수·북마크는 계약에 없는 값이라 만들지 않는다.
 */
export function StoreCard({ store, categoryNames, detailSearch }: Props) {
  const availability = store.reservationAvailability
  const art = categoryArt(store.storeCategoryCode)
  const tint = categoryTint(store.storeCategoryCode)
  const detailTo = { pathname: `/stores/${store.storeId}`, search: detailSearch }

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

      <div className="mi-card__body store-card__body">
        <h3 className="store-card__name">
          <Link to={detailTo}>{store.name}</Link>
        </h3>

        <p className="store-card__meta">
          {`${REGION_LABEL[store.region]} · ${catalogLabel(store.storeCategoryCode, categoryNames)}`}
        </p>

        <p className="store-card__address">
          <Icon name="pin" className="mi-icon--sm" />
          {store.address}
        </p>

        <StoreModeList modes={store.modes} />

        {/* 3분기를 뱃지 색뿐 아니라 문장으로도 구분한다. */}
        <p
          className={`store-card__availability store-card__availability--${availability.toLowerCase()}`}
        >
          <Icon name={AVAILABILITY_ICON[availability]} className="mi-icon--sm" />
          {AVAILABILITY_DESCRIPTION[availability]}
        </p>

        {/*
          시안은 카드마다 "예약하기" 버튼을 둔다. 예약을 받지 않는 매장까지
          같은 버튼을 두면 누른 뒤에야 막히므로, 그런 매장은 상세로 보낸다.

          `reservationEnabled`만 보면 임시 휴무·폐점 매장에도 버튼이 뜬다.
          서버가 거절할 쓰기 화면으로 보내는 셈이라 영업 상태도 함께 본다.
          매장 상세의 `StoreTransactionActions`와 같은 기준이다.

          검색 조건을 그대로 넘긴다. 예약 작성 화면의 draft가 serviceDate·
          startTime을 읽고 partySize를 adultCount로 받으므로, 방금 검색한
          조건이 그대로 이어진다. 매장 상세의 예약 버튼과 같은 방식이다.
        */}
        <div className="store-card__foot">
          {store.operationStatus === 'OPEN' && store.modes.reservationEnabled ? (
            <Link
              className="mi-button mi-button--primary mi-button--block"
              to={{
                pathname: `/stores/${store.storeId}/reserve`,
                search: detailSearch,
              }}
            >
              예약하기
            </Link>
          ) : (
            <Link
              className="mi-button mi-button--ghost mi-button--block"
              to={detailTo}
            >
              매장 자세히 보기
            </Link>
          )}
        </div>
      </div>
    </li>
  )
}

/**
 * 매장이 활성화한 거래 방식만 나열한다.
 * 꺼진 방식은 비활성 버튼으로 남기지 않고 아예 표시하지 않는다.
 *
 * 시안 카드의 해시태그 자리다. 계약에 매장 태그가 없으므로 실제로 이용할 수
 * 있는 방식을 같은 자리에 둔다.
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
    <ul className="store-card__modes" aria-label="이용 가능한 방식">
      {enabled.map((label) => (
        <li key={label} className="mi-tag">
          {label}
        </li>
      ))}
    </ul>
  )
}
