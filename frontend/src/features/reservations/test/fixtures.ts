import type { components as MenuHoldComponents } from '../../../shared/api/generated/menu-hold-pickup'
import type { ReservationDetail } from '../model/draft'

export const STORE_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1XA'
export const MENU_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1MA'
export const RESERVATION_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1R1'

export const RESERVATIONS_PATH = '/api/v1/consumers/reservations'
export const RESERVATION_DETAIL_PATH =
  '/api/v1/consumers/reservations/:reservationId'
export const RESERVATION_CANCEL_PATH =
  '/api/v1/consumers/reservations/:reservationId/cancellations'
export const MENU_HOLD_AVAILABILITY_PATH =
  '/api/v1/stores/:storeId/menu-hold-availability'

type MenuHoldAvailabilityData =
  MenuHoldComponents['schemas']['MenuHoldAvailabilityData']
type MenuHoldAvailabilityItem =
  MenuHoldComponents['schemas']['MenuHoldAvailabilityItem']

export function menuHoldAvailability(
  itemOverrides: Partial<MenuHoldAvailabilityItem> = {},
): MenuHoldAvailabilityData {
  return {
    serviceDate: '2026-09-01',
    startAt: '2026-09-01T19:00:00+09:00',
    serviceEndAt: '2026-09-01T21:00:00+09:00',
    timeZoneId: 'Asia/Seoul',
    items: [
      {
        menuId: MENU_ID,
        menuName: '트러플 크림 파파델레',
        unitPrice: 32000,
        availableOnlineQuantity: 5,
        availabilityStatus: 'AVAILABLE',
        ...itemOverrides,
      },
    ],
  }
}

export function reservationDetail(
  overrides: Partial<ReservationDetail> = {},
): ReservationDetail {
  return {
    reservationId: RESERVATION_ID,
    storeId: STORE_ID,
    storeName: '파스타 마스터즈',
    serviceDate: '2026-09-01',
    timeStatus: 'RESOLVED',
    startAt: '2026-09-01T19:00:00+09:00',
    serviceEndAt: '2026-09-01T21:00:00+09:00',
    timeZoneId: 'Asia/Seoul',
    party: { adultCount: 2, childCount: 0, infantCount: 0, totalCount: 2 },
    status: 'CONFIRMED',
    menuSelections: [],
    cancelledBy: null,
    cancellationReason: null,
    createdAt: '2026-08-11T10:00:00+09:00',
    ...overrides,
  }
}
