import type { components } from '../../../../shared/api/generated/menu-hold-pickup'

export type PickupReservation = components['schemas']['PickupReservation']
export type PickupPageData = components['schemas']['PickupReservationPageData']
export type PickupStatus = components['schemas']['PickupStatus']

export const PICKUP_STATUS_LABEL: Record<PickupStatus, string> = {
  CONFIRMED: '예약 완료',
  PICKED_UP: '수령 완료',
  CANCELLED: '취소',
}
