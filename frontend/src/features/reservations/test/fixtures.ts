import { http } from 'msw'
import { successResponse } from '../../../test/msw/envelope'
import type { components as MenuHoldComponents } from '../../../shared/api/generated/menu-hold-pickup'
import { storeDetail } from '../../store-search/test/fixtures'
import type { ReservationDetail } from '../model/draft'

export const STORE_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1XA'
export const MENU_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1MA'
export const RESERVATION_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1R1'

export const RESERVATIONS_PATH = '/api/v1/consumers/me/reservations'
export const RESERVATION_DETAIL_PATH =
  '/api/v1/consumers/me/reservations/:reservationId'
export const RESERVATION_CANCEL_PATH =
  '/api/v1/consumers/me/reservations/:reservationId/cancellations'
export const MENU_HOLD_AVAILABILITY_PATH =
  '/api/v1/stores/:storeId/menu-hold-availability'
export const STORE_DETAIL_PATH = '/api/v1/stores/:storeId'

/**
 * 예약 작성 화면은 매장의 `modes.menuHoldEnabled`를 보고 메뉴 단계를 거칠지
 * 정한다. 그래서 이 화면 테스트에는 매장 상세 응답이 항상 필요하다.
 */
export function storeWithMenuHold(menuHoldEnabled = true) {
  return http.get(STORE_DETAIL_PATH, () =>
    successResponse(
      storeDetail({
        storeId: STORE_ID,
        modes: {
          reservationEnabled: true,
          menuHoldEnabled,
          pickupEnabled: false,
        },
      }),
    ),
  )
}

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
    depositDisposition: null,
    createdAt: '2026-08-11T10:00:00+09:00',
    ...overrides,
  }
}
