import { isApiError } from '../../../../shared/api/apiError'
import { isOutcomeUnknown } from '../../../../shared/api/idempotencyKey'
import { AccountErrorCode } from '../../../../shared/auth/authErrors'

/** backend `ReservationErrorCode`·`MenuHoldErrorCode`와 1:1로 맞춘다. */
export const ReservationErrorCode = {
  NOT_FOUND: 'RESERVATION_001',
  TIME_UNAVAILABLE: 'RESERVATION_002',
  CAPACITY_UNAVAILABLE: 'RESERVATION_003',
  DUPLICATE: 'RESERVATION_004',
  INVALID_STATE: 'RESERVATION_005',
  CANCELLATION_NOT_ALLOWED: 'RESERVATION_006',
  POLICY_VERSION_CHANGED: 'RESERVATION_007',
  PARTY_SIZE_NOT_ALLOWED: 'RESERVATION_009',
} as const

export const MenuHoldErrorCode = {
  INELIGIBLE: 'MENU_HOLD_001',
  INSUFFICIENT_QUANTITY: 'MENU_HOLD_002',
} as const

/**
 * 생성 실패에서 사용자가 취할 수 있는 복구 행동.
 *
 * 서버 code로만 판정한다. 메시지 문자열을 파싱하지 않는다.
 */
export type CreateRecovery =
  | { kind: 'reselectSchedule'; message: string }
  | { kind: 'reselectMenus'; message: string }
  | { kind: 'registerContact'; message: string }
  | { kind: 'retry'; message: string }
  | { kind: 'none'; message: string }

export function toCreateRecovery(error: unknown): CreateRecovery {
  /*
   * 서버 반영 여부를 모르는 실패다.
   *
   * 여기서 "다시 시도"를 권하면 첫 요청이 이미 커밋된 경우 같은 의도가 두 건이
   * 된다. 재시도 대신 결과를 확인하게 한다. 판정은 code 이전에 한다.
   */
  if (isOutcomeUnknown(error)) {
    return {
      kind: 'none',
      message:
        '예약 처리 여부를 확인하지 못했습니다. 다시 시도하지 말고 내 예약에서 상태를 확인해 주세요.',
    }
  }

  if (!isApiError(error)) {
    return {
      kind: 'retry',
      message: '예약을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.',
    }
  }

  switch (error.code) {
    case ReservationErrorCode.TIME_UNAVAILABLE:
      return {
        kind: 'reselectSchedule',
        message: '선택한 시간에는 예약할 수 없습니다. 다른 시간을 골라 주세요.',
      }
    case ReservationErrorCode.CAPACITY_UNAVAILABLE:
      return {
        kind: 'reselectSchedule',
        message:
          '방금 자리가 찼습니다. 다른 시간이나 인원으로 다시 확인해 주세요.',
      }
    case ReservationErrorCode.DUPLICATE:
      return {
        kind: 'none',
        message: '같은 시간에 이미 예약이 있습니다. 내 예약에서 확인해 주세요.',
      }
    case ReservationErrorCode.POLICY_VERSION_CHANGED:
      return {
        kind: 'reselectSchedule',
        message:
          '매장의 예약 정책이 방금 바뀌었습니다. 조건을 다시 확인해 주세요.',
      }
    case ReservationErrorCode.PARTY_SIZE_NOT_ALLOWED:
      return {
        kind: 'reselectSchedule',
        message: '이 매장이 허용하는 인원 범위가 아닙니다.',
      }
    case MenuHoldErrorCode.INELIGIBLE:
      return {
        kind: 'reselectMenus',
        message:
          '선택한 메뉴를 지금은 미리 주문할 수 없습니다. 메뉴를 다시 고르거나 메뉴 없이 예약해 주세요.',
      }
    case MenuHoldErrorCode.INSUFFICIENT_QUANTITY:
      return {
        kind: 'reselectMenus',
        message:
          '선택한 메뉴 수량이 부족합니다. 수량을 줄이거나 메뉴 없이 예약해 주세요.',
      }
    case AccountErrorCode.RESERVATION_CONTACT_REQUIRED:
      return {
        kind: 'registerContact',
        message: '예약 알림을 받을 연락처를 먼저 등록해야 합니다.',
      }
    default:
      return {
        kind: 'retry',
        message: '예약을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.',
      }
  }
}

/**
 * 취소 실패 문구.
 *
 * 취소는 상태 전이 불가와 정책 불가 두 가지로만 갈린다.
 * 생성 오류나 메뉴 재선택 안내를 취소 흐름에 섞지 않는다.
 */
export function toCancelMessage(error: unknown): string {
  // 취소도 서버에 반영됐을 수 있다. 재시도를 권하지 않고 상태 확인을 안내한다.
  if (isOutcomeUnknown(error)) {
    return '취소 처리 여부를 확인하지 못했습니다. 다시 시도하지 말고 최신 상태를 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '예약을 취소하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case ReservationErrorCode.INVALID_STATE:
      return '현재 상태에서는 취소할 수 없습니다. 최신 상태를 다시 확인해 주세요.'
    case ReservationErrorCode.CANCELLATION_NOT_ALLOWED:
      return '매장의 취소 정책상 지금은 취소할 수 없습니다.'
    default:
      return '예약을 취소하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
