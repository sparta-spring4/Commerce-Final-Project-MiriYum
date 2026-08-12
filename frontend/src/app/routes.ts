/**
 * 전체 route 표는 이 파일이 단독 소유한다.
 * 화면 Issue는 자기 route만 여기에 등록하고 라우터 설정을 각자 만들지 않는다.
 *
 * 1차 MVP에 없는 기능의 경로·네비게이션 항목·자리표시자는 만들지 않는다.
 */

/** 계정 shell. 두 shell은 인증 상태와 권한을 공유하지 않는다. */
export type Shell = 'public' | 'consumer' | 'storeOperator'

export const ROUTES = {
  home: '/',
  stores: '/stores',
  storeDetail: '/stores/:storeId',
  consumerSignIn: '/sign-in',
  consumerSignUp: '/sign-up',
  consumerKakaoCallback: '/auth/kakao/callback',
  consumerKakaoSignUp: '/auth/kakao/sign-up',
  myPage: '/mypage',
  myReservations: '/mypage/reservations',
  reservationCreate: '/stores/:storeId/reserve',
  reservationDetail: '/reservations/:reservationId',
  reservationComplete: '/reservations/:reservationId/complete',
  pickupCreate: '/stores/:storeId/pickup',
  pickupDetail: '/pickup-reservations/:pickupReservationId',
  pickupComplete: '/pickup-reservations/:pickupReservationId/complete',
  storeOperatorSignIn: '/store-operator/sign-in',
  storeOperatorSignUp: '/store-operator/sign-up',
  storeOperatorHome: '/store-operator',
  storeOperatorStoreCreate: '/store-operator/stores/new',
  storeOperatorStore: '/store-operator/stores/:storeId',
  storeOperatorOperatingHours:
    '/store-operator/stores/:storeId/operating-hours',
  storeOperatorReservationTimeSlots:
    '/store-operator/stores/:storeId/reservation-time-slots',
  storeOperatorClosures: '/store-operator/stores/:storeId/closures',
  storeOperatorMenus: '/store-operator/stores/:storeId/menus',
  storeOperatorMenuCreate: '/store-operator/stores/:storeId/menus/new',
  storeOperatorMenu: '/store-operator/stores/:storeId/menus/:menuId',
  storeOperatorReservationCapacities:
    '/store-operator/stores/:storeId/reservation-capacities',
  storeOperatorReservationTimePolicy:
    '/store-operator/stores/:storeId/reservation-time-policy',
  storeOperatorReservations: '/store-operator/stores/:storeId/reservations',
  storeOperatorReservation:
    '/store-operator/stores/:storeId/reservations/:reservationId',
  forbidden: '/forbidden',
} as const

/**
 * `:storeId` 등 경로 변수를 채운다.
 *
 * 화면이 문자열을 직접 이어 붙이면 route 표와 실제 이동 경로가 조용히 갈라진다.
 * 항상 ROUTES의 패턴을 입력으로 받아 여기서만 치환한다.
 */
export function fillPath(
  pattern: string,
  params: Readonly<Record<string, string | number>>,
): string {
  let path = pattern
  for (const [name, value] of Object.entries(params)) {
    path = path.replace(`:${name}`, encodeURIComponent(String(value)))
  }
  return path
}

/** 각 shell의 로그인 시작점. 교차 shell로 보내지 않는다. */
export const SIGN_IN_PATH: Record<Exclude<Shell, 'public'>, string> = {
  consumer: ROUTES.consumerSignIn,
  storeOperator: ROUTES.storeOperatorSignIn,
}

export interface NavigationItem {
  label: string
  path: string
}

/**
 * shell별 네비게이션 항목. 화면 Issue가 자기 항목을 추가한다.
 * 웨이팅·결제·리뷰·채팅처럼 뒤 단계 기능의 항목은 넣지 않는다.
 */
export const NAVIGATION: Record<Shell, NavigationItem[]> = {
  public: [{ label: '매장 찾기', path: ROUTES.stores }],
  consumer: [
    { label: '매장 찾기', path: ROUTES.stores },
    { label: '내 예약', path: ROUTES.myReservations },
    { label: '마이페이지', path: ROUTES.myPage },
  ],
  /**
   * 매장 운영자 항목은 매장 ID가 있어야 정해지므로 정적 목록으로 두지 않는다.
   * `storeOperatorNavigation`이 소유한다.
   */
  storeOperator: [],
}

/**
 * 매장 운영자 사이드바 항목.
 *
 * 운영 현황·메뉴 수량·웨이팅·통계는 1차 MVP 계약이 없어 넣지 않는다.
 * 예약 관리 항목은 #193 화면으로 이동만 하고 이 파일이 경로를 소유한다.
 */
export function storeOperatorNavigation(
  storeId: string | number,
): NavigationItem[] {
  const params = { storeId }
  return [
    { label: '매장 정보', path: fillPath(ROUTES.storeOperatorStore, params) },
    {
      label: '영업시간',
      path: fillPath(ROUTES.storeOperatorOperatingHours, params),
    },
    {
      label: '예약 접수 시간대',
      path: fillPath(ROUTES.storeOperatorReservationTimeSlots, params),
    },
    { label: '휴무·휴점', path: fillPath(ROUTES.storeOperatorClosures, params) },
    { label: '메뉴 관리', path: fillPath(ROUTES.storeOperatorMenus, params) },
    {
      label: '예약 수용량',
      path: fillPath(ROUTES.storeOperatorReservationCapacities, params),
    },
    {
      label: '예약 시간 정책',
      path: fillPath(ROUTES.storeOperatorReservationTimePolicy, params),
    },
    {
      label: '예약 목록',
      path: fillPath(ROUTES.storeOperatorReservations, params),
    },
  ]
}
