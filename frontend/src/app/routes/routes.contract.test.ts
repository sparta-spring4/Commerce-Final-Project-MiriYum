import { describe, expect, test } from 'vitest'
import { PUBLIC_PATHS } from './paths/publicPaths'
import { CONSUMER_PATHS } from './paths/consumerPaths'
import { STORE_OPERATOR_PATHS } from './paths/storeOperatorPaths'
import { PLATFORM_OPERATOR_PATHS } from './paths/platformOperatorPaths'
import { PUBLIC_NAVIGATION } from '../shells/public/navigation'
import { CONSUMER_NAVIGATION } from '../shells/consumer/navigation'
import { storeOperatorNavigation } from '../shells/store-operator/navigation'
import { PLATFORM_OPERATOR_NAVIGATION } from '../shells/platform-operator/navigation'

describe('사용자별 route 계약', () => {
  test('공개 URL을 그대로 유지한다', () => {
    expect(PUBLIC_PATHS).toEqual({
      home: '/',
      stores: '/stores',
      storeDetail: '/stores/:storeId',
      forbidden: '/forbidden',
    })
  })

  test('일반 사용자 URL을 그대로 유지한다', () => {
    expect(CONSUMER_PATHS).toEqual({
      signIn: '/sign-in',
      signUp: '/sign-up',
      kakaoCallback: '/auth/kakao/callback',
      kakaoSignUp: '/auth/kakao/sign-up',
      myPage: '/mypage',
      myReservations: '/mypage/reservations',
      myPickups: '/mypage/pickups',
      notificationHistory: '/mypage/notifications',
      reservationCreate: '/stores/:storeId/reserve',
      reservationDetail: '/reservations/:reservationId',
      reservationComplete: '/reservations/:reservationId/complete',
      reservationPayment: '/reservation-requests/:reservationRequestId/payment',
      pickupCreate: '/stores/:storeId/pickup',
      pickupDetail: '/pickup-reservations/:pickupReservationId',
      pickupComplete: '/pickup-reservations/:pickupReservationId/complete',
      waitingInvitationAccept: '/waiting/invitations/accept',
      waitingRegister: '/stores/:storeId/waiting',
    })
  })

  test('매장 운영자 URL을 그대로 유지한다', () => {
    expect(STORE_OPERATOR_PATHS).toEqual({
      signIn: '/store-operator/sign-in',
      signUp: '/store-operator/sign-up',
      home: '/store-operator',
      storeCreate: '/store-operator/stores/new',
      store: '/store-operator/stores/:storeId',
      operatingHours: '/store-operator/stores/:storeId/operating-hours',
      reservationTimeSlots:
        '/store-operator/stores/:storeId/reservation-time-slots',
      closures: '/store-operator/stores/:storeId/closures',
      menus: '/store-operator/stores/:storeId/menus',
      menuCreate: '/store-operator/stores/:storeId/menus/new',
      menu: '/store-operator/stores/:storeId/menus/:menuId',
      reservationCapacities:
        '/store-operator/stores/:storeId/reservation-capacities',
      reservationTimePolicy:
        '/store-operator/stores/:storeId/reservation-time-policy',
      reservations: '/store-operator/stores/:storeId/reservations',
      reservation:
        '/store-operator/stores/:storeId/reservations/:reservationId',
      reservationVisits:
        '/store-operator/stores/:storeId/reservation-visits',
      pickupReservations:
        '/store-operator/stores/:storeId/pickup-reservations',
      pickupReservation:
        '/store-operator/stores/:storeId/pickup-reservations/:pickupReservationId',
      menuInventory:
        '/store-operator/stores/:storeId/menu-inventory',
      representativeMenus:
        '/store-operator/stores/:storeId/representative-menus',
      dashboardStatistics:
        '/store-operator/stores/:storeId/dashboard-statistics',
      waitingSettings: '/store-operator/stores/:storeId/waiting-settings',
      waitingTeams: '/store-operator/stores/:storeId/waiting-teams',
      waitingTeam:
        '/store-operator/stores/:storeId/waiting-teams/:waitingTeamId',
    })
  })

  test('플랫폼 운영자 URL을 그대로 유지한다', () => {
    expect(PLATFORM_OPERATOR_PATHS).toEqual({
      signIn: '/admin/login',
      initialPassword: '/admin/first-password-change',
      members: '/admin/members',
      memberDetail: '/admin/members/:accountType/:accountId',
      supportCases: '/admin/member-support-cases',
      supportCaseDetail: '/admin/member-support-cases/:caseId',
      operators: '/admin/operators',
      operatorCreate: '/admin/operators/new',
      operatorDetail: '/admin/operators/:operatorId',
      stores: '/admin/stores',
      storeDetail: '/admin/stores/:storeId',
      storeSanctionCase: '/admin/stores/:storeId/sanction-cases/:caseId',
      memberSanctionApproval: '/admin/member-sanctions/approvals',
      audit: '/admin/audit',
      auditDetail: '/admin/audit/:eventKey',
    })
  })
})

describe('사용자별 navigation 계약', () => {
  test('공개·일반 사용자 메뉴를 그대로 유지한다', () => {
    expect(PUBLIC_NAVIGATION).toEqual([
      { label: '매장 찾기', path: '/stores' },
    ])
    expect(CONSUMER_NAVIGATION).toEqual([
      { label: '매장 찾기', path: '/stores' },
      { label: '내 예약', path: '/mypage/reservations' },
      { label: '마이페이지', path: '/mypage' },
    ])
  })

  test('매장 운영자 메뉴와 URL 치환을 그대로 유지한다', () => {
    expect(storeOperatorNavigation('store/7')).toEqual([
      { label: '매장 정보', path: '/store-operator/stores/store%2F7' },
      {
        label: '영업시간',
        path: '/store-operator/stores/store%2F7/operating-hours',
      },
      {
        label: '예약 접수 시간대',
        path: '/store-operator/stores/store%2F7/reservation-time-slots',
      },
      { label: '휴무·휴점', path: '/store-operator/stores/store%2F7/closures' },
      { label: '메뉴 관리', path: '/store-operator/stores/store%2F7/menus' },
      {
        label: '예약 수용량',
        path: '/store-operator/stores/store%2F7/reservation-capacities',
      },
      {
        label: '예약 시간 정책',
        path: '/store-operator/stores/store%2F7/reservation-time-policy',
      },
      { label: '예약 목록', path: '/store-operator/stores/store%2F7/reservations' },
      { label: '체크인·노쇼', path: '/store-operator/stores/store%2F7/reservation-visits' },
      {
        label: '픽업 목록',
        path: '/store-operator/stores/store%2F7/pickup-reservations',
      },
      {
        label: '메뉴 재고',
        path: '/store-operator/stores/store%2F7/menu-inventory',
      },
      {
        label: '추천 메뉴',
        path: '/store-operator/stores/store%2F7/representative-menus',
      },
      {
        label: '운영 통계',
        path: '/store-operator/stores/store%2F7/dashboard-statistics',
      },
      {
        label: '웨이팅 설정',
        path: '/store-operator/stores/store%2F7/waiting-settings',
      },
      {
        label: '웨이팅 목록',
        path: '/store-operator/stores/store%2F7/waiting-teams',
      },
    ])
  })

  test('플랫폼 운영자 메뉴와 권한을 그대로 유지한다', () => {
    expect(PLATFORM_OPERATOR_NAVIGATION).toEqual([
      {
        label: '회원 관리',
        path: '/admin/members',
        permissions: ['MEMBER_READ_MINIMAL'],
      },
      {
        label: '회원지원 사건',
        path: '/admin/member-support-cases',
        permissions: ['MEMBER_RECOVERY', 'ACCOUNT_APPEAL_REVIEW'],
      },
      {
        label: '매장 관리',
        path: '/admin/stores',
        permissions: ['STORE_READ_MINIMAL'],
      },
      {
        label: '영구 정지 승인',
        path: '/admin/member-sanctions/approvals',
        permissions: ['ACCOUNT_PERMANENT_SANCTION_APPROVE'],
      },
      {
        label: '운영자 관리',
        path: '/admin/operators',
        permissions: ['OPERATOR_AUTHORITY_MANAGE'],
      },
      {
        label: '감사 이력',
        path: '/admin/audit',
        permissions: ['AUDIT_READ'],
      },
    ])
  })
})
