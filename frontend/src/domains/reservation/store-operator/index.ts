/**
 * 매장 운영자 예약 운영 화면(#193)의 공개 진입점.
 *
 * 셸은 `app/shells/store-operator`, 게시 컴포넌트는 store 도메인이 소유한다. 여기서는 예약 도메인
 * query와 화면만 내보낸다. 스타일은 운영자 셸 CSS를 그대로 쓴다.
 */
export { ReservationCapacityPage } from './ui/ReservationCapacityPage'
export { ReservationTimePolicyPage } from './ui/ReservationTimePolicyPage'
export { StoreReservationsPage } from './ui/StoreReservationsPage'
export { StoreReservationDetailPage } from './ui/StoreReservationDetailPage'
