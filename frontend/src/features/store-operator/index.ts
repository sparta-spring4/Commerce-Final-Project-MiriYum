/**
 * 매장 운영자 셸의 공개 진입점.
 *
 * 일반 사용자 셸은 별도 provider·가드·화면을 갖는다. 여기서 함께 내보내지 않는다.
 * 화면 CSS는 이 파일에서 한 번만 불러온다.
 */
import './ui/partner.css'

export {
  StoreOperatorAuthProvider,
  useStoreOperatorAuth,
  type StoreOperatorAuthStatus,
} from './StoreOperatorAuthProvider'
export { RequireStoreOperatorAuth } from './RequireStoreOperatorAuth'
export {
  CurrentStoreProvider,
  useAdoptStoreFromRoute,
  useCurrentStore,
} from './CurrentStoreProvider'
export { storeOperatorKeys, useManagedStore } from './api/queries'
export { storeErrorMessage } from './model/storeErrors'

export { StoreOperatorLayout } from './ui/StoreOperatorLayout'
export { StoreOperatorSignInPage } from './ui/StoreOperatorSignInPage'
export { StoreOperatorSignUpPage } from './ui/StoreOperatorSignUpPage'
export { StoreOperatorHomePage } from './ui/StoreOperatorHomePage'
export { StoreCreatePage } from './ui/StoreCreatePage'
export { StoreInfoPage } from './ui/StoreInfoPage'
export { OperatingHoursPage } from './ui/OperatingHoursPage'
export { ReservationTimeSlotsPage } from './ui/ReservationTimeSlotsPage'
export { ClosuresPage } from './ui/ClosuresPage'
export { MenuListPage } from './ui/MenuListPage'
export { MenuEditorPage } from './ui/MenuEditorPage'

/**
 * 예약 운영 화면(#193)이 함께 쓰는 조각.
 *
 * 게시 모델과 셸은 같은 운영자 계약에서 나온 것이라 두 곳에 복제하지 않는다.
 * 반대로 예약 도메인 query·화면은 그쪽 기능 폴더가 소유한다.
 */
export { PageHeader, SectionCard, SummaryList } from './ui/PageHeader'
export {
  PublicationControls,
  type PublicationSubmission,
} from './ui/PublicationControls'
