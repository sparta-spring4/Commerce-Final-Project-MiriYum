import { Alert, EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Icon } from '../../../../shared/ui/Icon'
import { formatPrice } from '../../../store/public/model/labels'
import { useMenuHoldAvailability } from '../api/queries'
import {
  MAX_MENU_QUANTITY,
  withMenuQuantity,
  type ReservationDraft,
} from '../model/draft'

interface Props {
  storeId: string
  draft: ReservationDraft
  onChange: (next: ReservationDraft) => void
}

/**
 * 대표 메뉴 사전 선택.
 *
 * 선택은 예약 생성 쓰기에 함께 담긴다. 여기서 별도의 홀드 쓰기를 부르지 않는다.
 * 표시하는 잔여 수량은 미리보기이며 최종 확보를 보장하지 않는다.
 */
export function MenuSelectionStep({ storeId, draft, onChange }: Props) {
  const availability = useMenuHoldAvailability(
    storeId,
    draft.serviceDate,
    draft.startTime,
    draft.serviceDate.length > 0 && draft.startTime.length > 0,
  )

  if (availability.isPending) {
    return <Loading label="메뉴 수량을 확인하는 중입니다." />
  }

  if (availability.isError) {
    return (
      <ErrorState
        error={availability.error}
        message="메뉴 수량을 확인하지 못했습니다. 메뉴 없이 예약을 이어갈 수 있습니다."
        onRetry={() => void availability.refetch()}
      />
    )
  }

  const items = availability.data.items

  if (items.length === 0) {
    return (
      <EmptyState
        title="미리 선택할 수 있는 메뉴가 없습니다."
        description="메뉴 선택 없이 예약을 이어갈 수 있습니다."
      />
    )
  }

  return (
    <div className="menu-selection">
      <Alert tone="info" title="지금 보이는 수량은 확정이 아닙니다.">
        <p>
          예약을 만드는 시점에 서버가 남은 수량을 다시 확인합니다. 당일 매장
          상황에 따라 조기 품절될 수 있습니다.
        </p>
      </Alert>

      <ul className="menu-selection__list">
        {items.map((item) => {
          const selected = draft.menuSelections.get(item.menuId) ?? 0
          const soldOut = item.availabilityStatus !== 'AVAILABLE'
          // 서버가 알려 준 잔여를 넘겨 고르지 않게 막는다. 최종 검증은 쓰기에서 한다.
          const max = Math.min(item.availableOnlineQuantity, MAX_MENU_QUANTITY)

          return (
            <li
              key={item.menuId}
              className={[
                'mi-card',
                'mi-card--roomy',
                'menu-selection__item',
                // 시안은 재료 소진 메뉴를 흐리게 낮춘다. 목록에서 지우지는 않는다.
                soldOut ? 'menu-selection__item--muted' : null,
                selected > 0 ? 'menu-selection__item--picked' : null,
              ]
                .filter(Boolean)
                .join(' ')}
            >
              <div className="mi-card__body">
                <div className="menu-selection__head">
                  <h3>{item.menuName}</h3>
                  {soldOut && <span className="mi-badge mi-badge--negative">품절</span>}
                </div>
                <p className="menu-selection__price">
                  {formatPrice(item.unitPrice)}
                </p>
                <p className="menu-selection__stock">
                  {soldOut
                    ? '지금은 선택할 수 없습니다.'
                    : `남은 수량 ${item.availableOnlineQuantity}개`}
                </p>

                {!soldOut && (
                  <div className="menu-selection__quantity mi-counter">
                    <button
                      type="button"
                      className="mi-counter__button"
                      aria-label={`${item.menuName} 수량 줄이기`}
                      disabled={selected <= 0}
                      onClick={() =>
                        onChange(withMenuQuantity(draft, item.menuId, selected - 1))
                      }
                    >
                      <Icon name="minus" />
                    </button>
                    <output
                      className="mi-counter__value"
                      aria-label={`${item.menuName} 선택 수량`}
                    >
                      {selected}
                    </output>
                    <button
                      type="button"
                      className="mi-counter__button"
                      aria-label={`${item.menuName} 수량 늘리기`}
                      disabled={selected >= max}
                      onClick={() =>
                        onChange(withMenuQuantity(draft, item.menuId, selected + 1))
                      }
                    >
                      <Icon name="plus" />
                    </button>
                  </div>
                )}
              </div>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
