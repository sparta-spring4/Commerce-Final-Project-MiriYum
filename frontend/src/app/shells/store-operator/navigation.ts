import { fillPath, type NavigationItem } from '../../routes/path'
import { STORE_OPERATOR_PATHS } from '../../routes/paths/storeOperatorPaths'

export function storeOperatorNavigation(
  storeId: string | number,
): NavigationItem[] {
  const params = { storeId }
  return [
    { label: '매장 정보', path: fillPath(STORE_OPERATOR_PATHS.store, params) },
    {
      label: '영업시간',
      path: fillPath(STORE_OPERATOR_PATHS.operatingHours, params),
    },
    {
      label: '예약 접수 시간대',
      path: fillPath(STORE_OPERATOR_PATHS.reservationTimeSlots, params),
    },
    { label: '휴무·휴점', path: fillPath(STORE_OPERATOR_PATHS.closures, params) },
    { label: '메뉴 관리', path: fillPath(STORE_OPERATOR_PATHS.menus, params) },
    {
      label: '예약 수용량',
      path: fillPath(STORE_OPERATOR_PATHS.reservationCapacities, params),
    },
    {
      label: '예약 시간 정책',
      path: fillPath(STORE_OPERATOR_PATHS.reservationTimePolicy, params),
    },
    { label: '예약 목록', path: fillPath(STORE_OPERATOR_PATHS.reservations, params) },
    {
      label: '웨이팅 설정',
      path: fillPath(STORE_OPERATOR_PATHS.waitingSettings, params),
    },
    {
      label: '웨이팅 목록',
      path: fillPath(STORE_OPERATOR_PATHS.waitingTeams, params),
    },
  ]
}
