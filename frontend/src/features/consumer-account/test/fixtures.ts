import type { ConsumerAccount } from '../api/queries'
import type { ReservationHistoryItem } from '../model/reservationDisplay'

export const CONSUMER_ME_PATH = '/api/v1/consumers/me'
export const CONSUMER_ME_CONTACT_PATH = '/api/v1/consumers/me/contact'
export const CONSUMER_ME_RESERVATIONS_PATH = '/api/v1/consumers/me/reservations'

export function consumerAccount(
  overrides: Partial<ConsumerAccount> = {},
): ConsumerAccount {
  return {
    accountId: '01JBQ8Z4T7K2N9V6M3P5R8W1AC',
    email: 'user@example.com',
    phoneNumber: '010-****-5678',
    nickname: '미리냠',
    status: 'ACTIVE',
    ...overrides,
  }
}

export function reservationHistoryItem(
  overrides: Partial<ReservationHistoryItem> = {},
): ReservationHistoryItem {
  return {
    reservationId: '01JBQ8Z4T7K2N9V6M3P5R8W1R1',
    storeId: '01JBQ8Z4T7K2N9V6M3P5R8W1XA',
    storeName: '파스타 마스터즈',
    serviceDate: '2026-09-01',
    timeStatus: 'RESOLVED',
    startAt: '2026-09-01T19:00:00+09:00',
    serviceEndAt: '2026-09-01T21:00:00+09:00',
    timeZoneId: 'Asia/Seoul',
    partySize: 2,
    status: 'CONFIRMED',
    createdAt: '2026-08-11T10:00:00+09:00',
    ...overrides,
  }
}

export function reservationHistoryPage(
  items: ReservationHistoryItem[],
  totalElements = items.length,
) {
  return {
    items,
    page: {
      number: 0,
      size: 20,
      totalElements,
      totalPages: Math.max(1, Math.ceil(totalElements / 20)),
      hasNext: totalElements > 20,
    },
  }
}
