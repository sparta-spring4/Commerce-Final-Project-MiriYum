import { describe, expect, test } from 'vitest'

describe('getNotificationPurposeLabel', () => {
  test.each([
    ['RESERVATION_CONFIRMED', '예약 확정'],
    ['RESERVATION_CHANGED', '예약 변경'],
    ['RESERVATION_REJECTED', '예약 거절'],
    ['RESERVATION_CANCELLED', '예약 취소'],
    ['RESERVATION_EXPIRED', '예약 만료'],
    ['RESERVATION_VISIT_REMINDER', '방문 예정 안내'],
    ['RESERVATION_COORDINATION_REQUIRED', '예약 조율 필요'],
    ['PICKUP_RESERVATION_CONFIRMED', '픽업 예약 확정'],
    ['PICKUP_RESERVATION_CANCELLED', '픽업 예약 취소'],
    ['MENU_HOLD_FULFILLMENT_AT_RISK', '예약 메뉴 확인 필요'],
    ['MENU_SUBSTITUTION_PROPOSED', '대체 메뉴 제안'],
    ['MENU_SUBSTITUTION_ACCEPTED', '대체 메뉴 수락'],
    ['MENU_SUBSTITUTION_REJECTED', '대체 메뉴 거절'],
    ['MENU_SUBSTITUTION_EXPIRED', '대체 메뉴 제안 만료'],
  ])('maps %s to %s', async (purpose, expectedLabel) => {
    const { getNotificationPurposeLabel } = await import(
      './notificationPurposeLabel'
    )

    expect(getNotificationPurposeLabel(purpose)).toBe(expectedLabel)
  })

  test('does not expose an unknown internal purpose code', async () => {
    const { getNotificationPurposeLabel } = await import(
      './notificationPurposeLabel'
    )

    expect(getNotificationPurposeLabel('FUTURE_INTERNAL_PURPOSE')).toBe('알림')
  })
})
