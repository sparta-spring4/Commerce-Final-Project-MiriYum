import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert, EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Icon } from '../../../shared/ui/Icon'
import { formatPrice } from '../../store-search/model/labels'
import { useCreatePickupReservation, usePickupAvailability } from '../api/queries'
import {
  MAX_PICKUP_QUANTITY,
  readPickupDraft,
  toPickupCreateMessage,
  toPickupCreateRequest,
  validatePickupDraft,
  withPickupQuantity,
  withPickupSlot,
  writePickupDraft,
  type PickupDraft,
  type PickupSlotAvailability,
} from '../model/pickup'

/**
 * 픽업 예약 작성.
 *
 * 날짜 → 시간대 → 메뉴 수량 순으로 고른다. 인원과 종료 시각은 계약에 없으므로
 * 입력받지 않는다.
 */
export function PickupCreatePage() {
  const { storeId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  const draft = readPickupDraft(searchParams)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [attemptKey, setAttemptKey] = useState(createIdempotencyKey)

  const availability = usePickupAvailability(
    storeId,
    draft.pickupDate,
    draft.pickupDate.length > 0,
  )
  const mutation = useCreatePickupReservation()

  function updateDraft(next: PickupDraft) {
    setFormError(null)
    // 입력이 바뀌면 이전 시도의 멱등 키를 버린다.
    setAttemptKey(createIdempotencyKey())
    setSearchParams(writePickupDraft(next), { replace: true })
  }

  function submit() {
    const validation = validatePickupDraft(draft)
    setErrors(validation)
    setFormError(null)
    if (Object.keys(validation).length > 0) {
      return
    }

    mutation.mutate(
      { body: toPickupCreateRequest(storeId, draft), idempotencyKey: attemptKey },
      {
        onSuccess: (reservation) => {
          if (reservation.status !== 'CONFIRMED') {
            setFormError(
              '픽업 예약 결과를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.',
            )
            return
          }
          void navigate(
            `/pickup-reservations/${reservation.pickupReservationId}/complete`,
            { replace: true },
          )
        },
        /*
         * 실패해도 멱등 키를 바꾸지 않는다. 응답만 유실된 경우 첫 요청이
         * 이미 커밋됐을 수 있고, 새 키로 다시 보내면 픽업이 두 건 잡힌다.
         * 키는 사용자가 입력을 바꿀 때만 새로 만든다(updateDraft).
         */
        onError: (error) => setFormError(toPickupCreateMessage(error)),
      },
    )
  }

  const selectedSlot = availability.data?.slots.find(
    (slot) => slot.pickupTime === draft.pickupTime,
  )

  return (
    <div className="mi-container mi-container--narrow pickup-create">
      <header className="mi-page-head pickup-create__header">
        <p className="pickup-create__back">
          <Link to={`/stores/${storeId}`}>
            <Icon name="arrowLeft" className="mi-icon--sm" />
            매장 상세로 돌아가기
          </Link>
        </p>
        <h1 className="mi-page-head__title">픽업 예약</h1>
        <p className="mi-page-head__lead">
          날짜와 시간대를 고르고 가져갈 메뉴를 담아 주세요.
        </p>
      </header>

      {formError !== null && <Alert tone="error" title={formError} />}

      <form
        className="pickup-form"
        aria-label="픽업 조건"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <section className="mi-card mi-card--roomy pickup-form__section">
          <div className="mi-card__body mi-card__body--roomy">
            <h2>픽업 날짜</h2>
            <TextField
              label="픽업 날짜"
              labelHidden
              type="date"
              name="pickupDate"
              required
              value={draft.pickupDate}
              error={errors.pickupDate ?? null}
              leadingIcon={<Icon name="calendar" />}
              onChange={(event) =>
                // 날짜가 바뀌면 구간과 메뉴 선택을 모두 다시 고른다.
                updateDraft({
                  pickupDate: event.target.value,
                  pickupTime: '',
                  menuSelections: new Map(),
                })
              }
            />
          </div>
        </section>

        {draft.pickupDate.length > 0 && (
          <SlotPicker
            availability={availability}
            draft={draft}
            error={errors.pickupTime ?? null}
            onSelect={(pickupTime) =>
              updateDraft(withPickupSlot(draft, pickupTime))
            }
          />
        )}

        {selectedSlot !== undefined && (
          <SlotMenus
            slot={selectedSlot}
            draft={draft}
            error={errors.menuSelections ?? null}
            onChange={updateDraft}
          />
        )}

        <Button
          type="submit"
          variant="primary"
          size="lg"
          block
          loading={mutation.isPending}
        >
          픽업 예약하기
        </Button>
      </form>
    </div>
  )
}

function SlotPicker({
  availability,
  draft,
  error,
  onSelect,
}: {
  availability: ReturnType<typeof usePickupAvailability>
  draft: PickupDraft
  error: string | null
  onSelect: (pickupTime: string) => void
}) {
  if (availability.isPending) {
    return <Loading label="픽업 시간대를 확인하는 중입니다." />
  }

  if (availability.isError) {
    return (
      <ErrorState
        error={availability.error}
        message="픽업 시간대를 불러오지 못했습니다."
        onRetry={() => void availability.refetch()}
      />
    )
  }

  if (availability.data.slots.length === 0) {
    return (
      <EmptyState
        title="선택한 날짜에 픽업 가능한 시간대가 없습니다."
        description="다른 날짜를 골라 주세요."
      />
    )
  }

  return (
    <fieldset className="mi-card mi-card--roomy pickup-form__slots">
      <legend>픽업 시간대</legend>
      <div className="pickup-form__slot-list">
        {availability.data.slots.map((slot) => (
          <button
            key={slot.pickupTime}
            type="button"
            className="pickup-form__slot"
            aria-pressed={draft.pickupTime === slot.pickupTime}
            onClick={() => onSelect(slot.pickupTime)}
          >
            {slot.pickupTime}
          </button>
        ))}
      </div>
      {error !== null && <p className="mi-field__error">{error}</p>}
    </fieldset>
  )
}

function SlotMenus({
  slot,
  draft,
  error,
  onChange,
}: {
  slot: PickupSlotAvailability
  draft: PickupDraft
  error: string | null
  onChange: (next: PickupDraft) => void
}) {
  if (slot.menus.length === 0) {
    return (
      <EmptyState title="이 시간대에 픽업할 수 있는 메뉴가 없습니다." />
    )
  }

  return (
    <fieldset className="mi-card mi-card--roomy pickup-form__menus">
      <legend>픽업 메뉴</legend>

      <Alert tone="info" title="지금 보이는 수량은 확정이 아닙니다.">
        <p>예약을 만드는 시점에 서버가 남은 수량을 다시 확인합니다.</p>
      </Alert>

      <ul className="pickup-form__menu-list">
        {slot.menus.map((menu) => {
          const selected = draft.menuSelections.get(menu.menuId) ?? 0
          const soldOut = menu.availabilityStatus !== 'AVAILABLE'
          const max = Math.min(menu.availableQuantity, MAX_PICKUP_QUANTITY)

          return (
            <li
              key={menu.menuId}
              className={[
                'pickup-form__menu',
                // 시안은 팔지 않는 메뉴를 흐리게 낮춘다. 목록에서 지우지 않는다.
                soldOut ? 'pickup-form__menu--muted' : null,
                selected > 0 ? 'pickup-form__menu--picked' : null,
              ]
                .filter(Boolean)
                .join(' ')}
            >
              <div className="pickup-form__menu-info">
                <div className="pickup-form__menu-head">
                  <h3>{menu.menuName}</h3>
                  {soldOut && (
                    <span className="mi-badge mi-badge--negative">품절</span>
                  )}
                </div>
                <p className="pickup-form__menu-price">
                  {formatPrice(menu.unitPrice)}
                </p>
                <p className="pickup-form__menu-stock">
                  {soldOut
                    ? '지금은 선택할 수 없습니다.'
                    : `남은 수량 ${menu.availableQuantity}개`}
                </p>
              </div>

              {!soldOut && (
                <div className="pickup-form__quantity mi-counter">
                  <button
                    type="button"
                    className="mi-counter__button"
                    aria-label={`${menu.menuName} 수량 줄이기`}
                    disabled={selected <= 0}
                    onClick={() =>
                      onChange(
                        withPickupQuantity(draft, menu.menuId, selected - 1),
                      )
                    }
                  >
                    <Icon name="minus" />
                  </button>
                  <output
                    className="mi-counter__value"
                    aria-label={`${menu.menuName} 선택 수량`}
                  >
                    {selected}
                  </output>
                  <button
                    type="button"
                    className="mi-counter__button"
                    aria-label={`${menu.menuName} 수량 늘리기`}
                    disabled={selected >= max}
                    onClick={() =>
                      onChange(
                        withPickupQuantity(draft, menu.menuId, selected + 1),
                      )
                    }
                  >
                    <Icon name="plus" />
                  </button>
                </div>
              )}
            </li>
          )
        })}
      </ul>

      {error !== null && <p className="mi-field__error">{error}</p>}
    </fieldset>
  )
}
