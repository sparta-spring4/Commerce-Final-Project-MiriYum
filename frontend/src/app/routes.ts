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
  forbidden: '/forbidden',
} as const

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
  storeOperator: [],
}
