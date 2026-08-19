import type { components } from '../../../shared/api/generated/notification'

type NotificationPurpose = components['schemas']['NotificationPurpose']

const PURPOSE_LABELS = {
  RESERVATION_CONFIRMED: '예약 확정',
  RESERVATION_CHANGED: '예약 변경',
  RESERVATION_REJECTED: '예약 거절',
  RESERVATION_CANCELLED: '예약 취소',
  RESERVATION_EXPIRED: '예약 만료',
  RESERVATION_VISIT_REMINDER: '방문 예정 안내',
  RESERVATION_COORDINATION_REQUIRED: '예약 조율 필요',
  RESERVATION_VISIT_COMPLETED: '방문 완료',
  RESERVATION_NO_SHOW: '예약 노쇼 처리',
  PICKUP_RESERVATION_CONFIRMED: '픽업 예약 확정',
  PICKUP_RESERVATION_CANCELLED: '픽업 예약 취소',
  MENU_HOLD_FULFILLMENT_AT_RISK: '예약 메뉴 확인 필요',
  MENU_SUBSTITUTION_PROPOSED: '대체 메뉴 제안',
  MENU_SUBSTITUTION_ACCEPTED: '대체 메뉴 수락',
  MENU_SUBSTITUTION_REJECTED: '대체 메뉴 거절',
  MENU_SUBSTITUTION_EXPIRED: '대체 메뉴 제안 만료',
  WAITING_ENTRY_IMMINENT: '입장 임박',
  WAITING_CALLED: '입장 호출',
  WAITING_CANCELLED: '웨이팅 취소',
  WAITING_NO_SHOW: '미응답 종료',
  WAITING_CHECKED_IN: '입장 완료',
  WAITING_CLOSED_BY_STORE: '매장 마감 종료',
} satisfies Record<NotificationPurpose, string>

export function getNotificationPurposeLabel(purpose: string): string {
  return Object.hasOwn(PURPOSE_LABELS, purpose)
    ? PURPOSE_LABELS[purpose as NotificationPurpose]
    : '알림'
}
