import { Badge } from '../../../shared/ui/Badge'
import { EmptyState } from '../../../shared/ui/Feedback'
import {
  SALE_STATUS_LABEL,
  SALE_STATUS_TONE,
  catalogLabel,
  formatPrice,
} from '../model/labels'
import type { PublicMenu } from '../model/searchParams'

interface Props {
  menus: PublicMenu[]
  menuCategoryNames: ReadonlyMap<string, string>
  emptyTitle: string
}

/**
 * 공개 메뉴 목록.
 *
 * 이미지 필드가 1차 MVP 계약에 없다. 시안의 사진 자리는 만들지 않는다.
 * 품절·판매 중지는 색이 아니라 뱃지 문구로 구분한다.
 */
export function MenuList({ menus, menuCategoryNames, emptyTitle }: Props) {
  if (menus.length === 0) {
    return <EmptyState title={emptyTitle} />
  }

  return (
    <ul className="menu-list">
      {menus.map((menu) => (
        <li key={menu.menuId} className="mi-card menu-list__item">
          <div className="mi-card__body">
            <div className="menu-list__head">
              <h4 className="menu-list__name">{menu.name}</h4>
              <Badge tone={SALE_STATUS_TONE[menu.saleStatus]}>
                {SALE_STATUS_LABEL[menu.saleStatus]}
              </Badge>
            </div>

            <p className="menu-list__price">{formatPrice(menu.price)}</p>

            {menu.description.length > 0 && (
              <p className="menu-list__description">{menu.description}</p>
            )}

            <p className="menu-list__category">
              <span className="visually-hidden">카테고리: </span>
              {catalogLabel(menu.primaryCategoryCode, menuCategoryNames)}
            </p>

            {menu.localTags.length > 0 && (
              <ul className="menu-list__tags">
                {menu.localTags.map((tag) => (
                  <li key={tag} className="mi-badge mi-badge--neutral">
                    {tag}
                  </li>
                ))}
              </ul>
            )}

            <MenuCapabilities menu={menu} />
          </div>
        </li>
      ))}
    </ul>
  )
}

/**
 * 메뉴별로 어떤 거래에 쓸 수 있는지 표시한다.
 * 계약이 holdEnabled와 pickupEnabled를 따로 주므로 하나로 합치지 않는다.
 */
function MenuCapabilities({ menu }: { menu: PublicMenu }) {
  const capabilities = [
    menu.representative ? '대표 메뉴' : null,
    menu.holdEnabled ? '예약 시 미리 선택 가능' : null,
    menu.pickupEnabled ? '픽업 가능' : null,
  ].filter((label): label is string => label !== null)

  if (capabilities.length === 0) {
    return null
  }

  return <p className="menu-list__capabilities">{capabilities.join(' · ')}</p>
}
