/**
 * 픽업 예약 기능의 공개 진입점.
 *
 * 일반 사용자 픽업 목록 Controller·OpenAPI가 없다. 목록 화면과 route를 만들지
 * 않고, 생성 성공 후 반환된 식별자로 상세에만 이동한다.
 */
import './ui/pickup.css'

export { PickupCompletePage } from './ui/PickupCompletePage'
export { PickupCreatePage } from './ui/PickupCreatePage'
export { PickupDetailPage } from './ui/PickupDetailPage'
