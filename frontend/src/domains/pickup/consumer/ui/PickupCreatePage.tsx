import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { useIdempotentAttempt } from '../../../../shared/api/useIdempotentAttempt'
import { Button } from '../../../../shared/ui/Button'
import { TextField } from '../../../../shared/ui/Field'
import { Alert, EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Icon } from '../../../../shared/ui/Icon'
import { formatPrice } from '../../../store/public/model/labels'
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

  /*
   * 멱등 키를 요청 지문과 함께 관리한다.
   *
   * 지문은 draft를 그대로 직렬화한 값이다. 날짜·시간대·메뉴 수량이 바뀌면
   * 다른 명령이므로 새 키를 쓰고, 그대로면 같은 키로 재시도한다.
   *
   * 결과 불명 뒤에는 지문이 바뀐 전송을 막는다. 첫 요청이 이미 커밋된 채
   * 응답만 유실됐다면, 조건을 바꿔 새 키로 보내는 순간 픽업이 두 건이 된다.
   * 그때는 같은 키 재전송(결과 확인)으로 먼저 결과를 확정해야 한다.
   */
  const fingerprint = writePickupDraft(draft).toString()
  const attempt = useIdempotentAttempt(fingerprint)

  const availability = usePickupAvailability(
    storeId,
    draft.pickupDate,
    draft.pickupDate.length > 0,
  )
  const mutation = useCreatePickupReservation()

  function updateDraft(next: PickupDraft) {
    setFormError(null)
    setSearchParams(writePickupDraft(next), { replace: true })
  }

  function submit() {
    const validation = validatePickupDraft(draft)
    setErrors(validation)
    setFormError(null)
    if (Object.keys(validation).length > 0) {
      return
    }

    const idempotencyKey = attempt.begin()
    if (idempotencyKey === null) {
      setFormError(
        '앞선 요청의 처리 여부를 확인하지 못했습니다. 조건을 바꿔 다시 보내면 픽업이 두 건 잡힐 수 있습니다. 조건을 되돌려 "예약 결과 확인"을 먼저 눌러 주세요.',
      )
      return
    }

    mutation.mutate(
      { body: toPickupCreateRequest(storeId, draft), idempotencyKey },
      {
        onSuccess: (reservation) => {
          attempt.settle(null)
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
        onError: (error) => {
          attempt.settle(error)
          setFormError(toPickupCreateMessage(error))
        },
      },
    )
  }

  /*
   * 결과가 불명인 동안에는 조건을 잠근다.
   *
   * 잠그지 않으면 사용자가 조건을 바꾸는 순간 지문이 달라져 결과 확인 경로가
   * 막히고, 화면에는 되돌릴 방법이 남지 않는다. draft가 URL에 있어 뒤로가기로도
   * 바뀔 수 있으므로 `attempt.begin()`의 차단이 마지막 방어선으로 함께 남는다.
   */
  const locked = attempt.outcomeUnknown

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

      {formError !== null && (
        <Alert
          tone="error"
          title={formError}
          actions={
            locked ? (
              /*
                같은 키로 같은 요청을 다시 보낸다. 계약이 "같은 키와 전체 요청
                지문의 재시도는 저장된 최초 결과를 재생한다"고 정하므로, 앞선
                요청이 커밋됐다면 그 결과가 그대로 와서 완료 화면으로 이어지고,
                커밋되지 않았다면 이번에 처리된다. 어느 쪽이든 두 건이 되지 않는다.
              */
              <Button
                variant="ghost"
                size="sm"
                loading={mutation.isPending}
                onClick={submit}
              >
                예약 결과 확인
              </Button>
            ) : undefined
          }
        />
      )}

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
              disabled={locked}
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
            locked={locked}
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
            locked={locked}
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
          disabled={locked}
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
  locked,
  error,
  onSelect,
}: {
  availability: ReturnType<typeof usePickupAvailability>
  draft: PickupDraft
  /** 결과 불명 동안에는 조건을 바꿀 수 없다. */
  locked: boolean
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
            disabled={locked}
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
  locked,
  error,
  onChange,
}: {
  slot: PickupSlotAvailability
  draft: PickupDraft
  /** 결과 불명 동안에는 수량을 바꿀 수 없다. */
  locked: boolean
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
                    disabled={locked || selected <= 0}
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
                    disabled={locked || selected >= max}
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
