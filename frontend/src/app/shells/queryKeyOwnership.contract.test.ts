import { describe, expect, test } from 'vitest'
import { consumerReservationHistoryKeys } from '../../domains/reservation/consumer/api/historyQueries'
import { consumerPaymentKeys } from '../../domains/payment/consumer/api/queries'
import { consumerWaitingKeys } from '../../domains/waiting/consumer/api/queries'
import { storeOperatorKeys } from './store-operator/queryKeys'

describe('사용자 경계 query key 계약', () => {
  test('consumer 도메인별 key 값을 유지한다', () => {
    const query = {
      status: 'CONFIRMED' as const,
      page: 2,
      sort: 'createdAt,desc' as const,
    }
    expect(consumerReservationHistoryKeys.list(query)).toEqual([
      'consumer-account', 'me', 'reservations', query,
    ])
    expect(consumerPaymentKeys.all).toEqual(['consumer-account', 'me', 'payments'])
    expect(consumerWaitingKeys.current).toEqual([
      'consumer-account', 'me', 'waiting-teams', 'current',
    ])
  })

  test('store-operator 공통 접두사와 도메인 key 값을 유지한다', () => {
    expect(storeOperatorKeys.menu('store-1', 'menu-1')).toEqual([
      'store-operator', 'store-1', 'menus', 'menu-1',
    ])
    expect(storeOperatorKeys.reservationPages('store-1')).toEqual([
      'store-operator', 'store-1', 'reservations', 'page',
    ])
    expect(storeOperatorKeys.waitingSettings('store-1')).toEqual([
      'store-operator', 'store-1', 'waiting-settings',
    ])
  })
})
