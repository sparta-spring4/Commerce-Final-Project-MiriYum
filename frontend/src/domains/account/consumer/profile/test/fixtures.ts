import type { ConsumerAccount } from '../api/queries'
import type { ConsumerPayment, ConsumerPaymentHistory } from '../../../../payment/consumer/api/queries'
import type { ConsumerWaitingSnapshot } from '../../../../waiting/consumer/api/queries'
import type { ReservationHistoryItem } from '../../../../reservation/consumer/model/reservationDisplay'

export const CONSUMER_ME_PATH = '/api/v1/consumers/me'
export const CONSUMER_ME_CONTACT_PATH = '/api/v1/consumers/me/contact'
export const CONSUMER_ME_RESERVATIONS_PATH = '/api/v1/consumers/me/reservations'
export const CONSUMER_PAYMENTS_PATH = '/api/v1/consumers/me/payments'
export const CONSUMER_CURRENT_WAITING_PATH =
  '/api/v1/consumers/me/waiting-teams/current'

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

export function consumerPayment(
  overrides: Partial<ConsumerPayment> = {},
): ConsumerPayment {
  return {
    paymentId: '01JBQ8Z4T7K2N9V6M3P5R8W1P1',
    reservationReferenceId: '01JBQ8Z4T7K2N9V6M3P5R8W1R1',
    amountMinor: 25_000,
    refundedAmountMinor: 0,
    refundableAmountMinor: 25_000,
    currency: 'KRW',
    status: 'PAID',
    lastAttemptStatus: 'PAID',
    createdAt: '2026-08-19T12:00:00+09:00',
    paidAt: '2026-08-19T12:00:03+09:00',
    updatedAt: '2026-08-19T12:00:03+09:00',
    refunds: [],
    ...overrides,
  }
}

export function consumerPaymentHistory(
  items: ConsumerPayment[] = [],
): ConsumerPaymentHistory {
  return { items, nextCursor: null, hasNext: false }
}

export function consumerWaitingSnapshot(
  overrides: Partial<ConsumerWaitingSnapshot> = {},
): ConsumerWaitingSnapshot {
  return {
    waitingTeamId: '01JBQ8Z4T7K2N9V6M3P5R8W1W1',
    storeId: '01JBQ8Z4T7K2N9V6M3P5R8W1XA',
    businessDate: '2026-08-19',
    status: 'WAITING',
    queueSequence: 7,
    teamsAhead: 3,
    partySize: 2,
    createdAt: '2026-08-19T12:00:00+09:00',
    calledAt: null,
    arrivalDeadline: null,
    arrivedAt: null,
    cancelledAt: null,
    version: 1,
    memberships: [
      {
        membershipId: '01JBQ8Z4T7K2N9V6M3P5R8W1M1',
        role: 'REPRESENTATIVE',
        joinedAt: '2026-08-19T12:00:00+09:00',
        self: true,
      },
    ],
    ...overrides,
  }
}
