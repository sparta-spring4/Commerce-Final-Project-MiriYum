import type { BadgeTone } from '../../../../shared/ui/Badge'
import { isApiError } from '../../../../shared/api/apiError'
import { isOutcomeUnknown } from '../../../../shared/api/idempotencyKey'
import type { components } from '../../../../shared/api/generated/menu-hold-pickup'

export type PickupReservation = components['schemas']['PickupReservation']
export type PickupStatus = components['schemas']['PickupStatus']
export type PickupSlotAvailability =
  components['schemas']['PickupSlotAvailability']
export type PickupReservationCreateRequest =
  components['schemas']['PickupReservationCreateRequest']

/** 계약이 정한 픽업 상태. 일반 예약 상태와 섞지 않는다. */
export const PICKUP_STATUS_LABEL: Record<PickupStatus, string> = {
  CONFIRMED: '픽업 확정',
  PICKED_UP: '픽업 완료',
  CANCELLED: '취소됨',
}

export const PICKUP_STATUS_TONE: Record<PickupStatus, BadgeTone> = {
  CONFIRMED: 'positive',
  PICKED_UP: 'attention',
  CANCELLED: 'neutral',
}

export const PickupErrorCode = {
  NOT_FOUND: 'PICKUP_001',
  NOT_ELIGIBLE: 'PICKUP_002',
  SLOT_INVALID: 'PICKUP_003',
  INSUFFICIENT_QUANTITY: 'PICKUP_004',
  INVALID_STATE: 'PICKUP_005',
  CANCELLATION_NOT_ALLOWED: 'PICKUP_006',
} as const

/** 계약의 menuSelections 제약. 최소 1종을 골라야 생성할 수 있다. */
export const MIN_PICKUP_MENUS = 1
export const MAX_PICKUP_MENUS = 20
export const MAX_PICKUP_QUANTITY = 100

/**
 * 픽업 작성 draft.
 *
 * 픽업은 인원(partySize)과 종료 시각을 보내지 않는다. 일반 예약과 다른 계약이므로
 * 예약 draft를 재사용하지 않는다.
 */
export interface PickupDraft {
  pickupDate: string
  pickupTime: string
  menuSelections: ReadonlyMap<string, number>
}

const LOCAL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const LOCAL_TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/
const MENU_PARAM = 'menu'

export const EMPTY_PICKUP_DRAFT: PickupDraft = {
  pickupDate: '',
  pickupTime: '',
  menuSelections: new Map(),
}

export function readPickupDraft(search: URLSearchParams): PickupDraft {
  const menuSelections = new Map<string, number>()
  for (const raw of search.getAll(MENU_PARAM)) {
    const separator = raw.lastIndexOf(':')
    if (separator <= 0) {
      continue
    }
    const quantity = Number.parseInt(raw.slice(separator + 1), 10)
    if (
      Number.isInteger(quantity) &&
      quantity >= 1 &&
      quantity <= MAX_PICKUP_QUANTITY
    ) {
      menuSelections.set(raw.slice(0, separator), quantity)
    }
  }

  const pickupDate = search.get('pickupDate') ?? search.get('serviceDate')
  const pickupTime = search.get('pickupTime') ?? search.get('startTime')

  return {
    pickupDate:
      pickupDate !== null && LOCAL_DATE_PATTERN.test(pickupDate) ? pickupDate : '',
    pickupTime:
      pickupTime !== null && LOCAL_TIME_PATTERN.test(pickupTime) ? pickupTime : '',
    menuSelections,
  }
}

export function writePickupDraft(draft: PickupDraft): URLSearchParams {
  const search = new URLSearchParams()
  if (draft.pickupDate) {
    search.set('pickupDate', draft.pickupDate)
  }
  if (draft.pickupTime) {
    search.set('pickupTime', draft.pickupTime)
  }
  for (const [menuId, quantity] of draft.menuSelections) {
    search.append(MENU_PARAM, `${menuId}:${quantity}`)
  }
  return search
}

export function withPickupQuantity(
  draft: PickupDraft,
  menuId: string,
  quantity: number,
): PickupDraft {
  const next = new Map(draft.menuSelections)
  if (quantity <= 0) {
    next.delete(menuId)
  } else {
    next.set(menuId, Math.min(quantity, MAX_PICKUP_QUANTITY))
  }
  return { ...draft, menuSelections: next }
}

/**
 * 픽업 시간을 바꾸면 메뉴 선택을 비운다.
 *
 * 재고 버킷은 구간별로 다르다. 이전 구간에서 고른 수량을 그대로 들고 가면
 * 다른 구간의 재고를 고른 것처럼 보이고 생성에서 `PICKUP_004`가 난다.
 */
export function withPickupSlot(
  draft: PickupDraft,
  pickupTime: string,
): PickupDraft {
  if (draft.pickupTime === pickupTime) {
    return draft
  }
  return { ...draft, pickupTime, menuSelections: new Map() }
}

export function validatePickupDraft(
  draft: PickupDraft,
): Readonly<Record<string, string>> {
  const errors: Record<string, string> = {}

  if (!LOCAL_DATE_PATTERN.test(draft.pickupDate)) {
    errors.pickupDate = '픽업 날짜를 선택해 주세요.'
  }
  if (!LOCAL_TIME_PATTERN.test(draft.pickupTime)) {
    errors.pickupTime = '픽업 시간대를 선택해 주세요.'
  }
  if (draft.menuSelections.size < MIN_PICKUP_MENUS) {
    errors.menuSelections = '픽업할 메뉴를 한 가지 이상 선택해 주세요.'
  }
  if (draft.menuSelections.size > MAX_PICKUP_MENUS) {
    errors.menuSelections = `메뉴는 최대 ${MAX_PICKUP_MENUS}종까지 선택할 수 있습니다.`
  }

  return errors
}

/**
 * 생성 요청 본문.
 *
 * `endTime`과 `partySize`를 보내지 않는다. 스키마가 `additionalProperties: false`라
 * 일반 예약의 필드를 그대로 옮기면 400이다.
 */
export function toPickupCreateRequest(
  storeId: string,
  draft: PickupDraft,
): PickupReservationCreateRequest {
  return {
    storeId,
    pickupDate: draft.pickupDate,
    pickupTime: draft.pickupTime,
    menuSelections: [...draft.menuSelections].map(([menuId, quantity]) => ({
      menuId,
      quantity,
    })),
  }
}

export function pickupTotalPrice(
  reservation: Pick<PickupReservation, 'items'>,
): number {
  return reservation.items.reduce(
    (total, item) => total + item.unitPrice * item.quantity,
    0,
  )
}

/** 생성 실패 문구. 서버 code로만 분기한다. */
export function toPickupCreateMessage(error: unknown): string {
  /*
   * 서버 반영 여부를 모르는 실패.
   *
   * "내 예약에서 확인"으로 안내할 수 없다. 1차 MVP에 픽업 목록 화면이 없고,
   * 응답이 유실됐으면 `pickupReservationId`도 모른다. 대신 같은 키로 한 번 더
   * 보내면 계약이 저장된 최초 결과를 재생하므로 그 행동을 안내한다.
   * 새 예약이 생기지 않는다는 점을 함께 밝힌다.
   */
  if (isOutcomeUnknown(error)) {
    return '픽업 예약 처리 여부를 확인하지 못했습니다. "예약 결과 확인"을 누르면 같은 요청으로 결과만 조회하며, 예약이 두 건 잡히지 않습니다.'
  }
  if (!isApiError(error)) {
    return '픽업 예약을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case PickupErrorCode.NOT_ELIGIBLE:
      return '이 매장은 지금 픽업 예약을 받지 않습니다.'
    case PickupErrorCode.SLOT_INVALID:
      return '선택한 픽업 시간대를 사용할 수 없습니다. 시간대를 다시 골라 주세요.'
    case PickupErrorCode.INSUFFICIENT_QUANTITY:
      return '선택한 메뉴 수량이 부족합니다. 수량을 줄이거나 다른 메뉴를 골라 주세요.'
    default:
      return '픽업 예약을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}

/**
 * 취소 실패 문구.
 *
 * 취소는 상태 전이 불가와 정책 불가로만 갈린다. 생성 오류를 섞지 않는다.
 */
export function toPickupCancelMessage(error: unknown): string {
  if (isOutcomeUnknown(error)) {
    return '취소 처리 여부를 확인하지 못했습니다. 다시 시도하지 말고 최신 상태를 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '픽업 예약을 취소하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case PickupErrorCode.INVALID_STATE:
      return '현재 상태에서는 취소할 수 없습니다. 최신 상태를 다시 확인해 주세요.'
    case PickupErrorCode.CANCELLATION_NOT_ALLOWED:
      return '매장의 취소 정책상 지금은 취소할 수 없습니다.'
    default:
      return '픽업 예약을 취소하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
