/**
 * 매장 운영자 세션 아래의 query key 뿌리.
 *
 * store, menu, reservation, waiting 도메인이 공유하며 현재 매장이 바뀔 때
 * 접두사 하나로 이전 매장의 보호 query를 취소·제거할 수 있게 한다.
 */
export const storeOperatorKeys = {
  all: ['store-operator'] as const,
  store: (storeId: string) => [...storeOperatorKeys.all, storeId] as const,
  managedStore: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'managed-store'] as const,
  publishedStore: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'published-store'] as const,
  menus: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'menus'] as const,
  menu: (storeId: string, menuId: string) =>
    [...storeOperatorKeys.menus(storeId), menuId] as const,
  representativeMenus: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'representative-menus'] as const,
  reservations: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'reservations'] as const,
  reservationPages: (storeId: string) =>
    [...storeOperatorKeys.reservations(storeId), 'page'] as const,
  reservationPage: (storeId: string, query: object) =>
    [...storeOperatorKeys.reservationPages(storeId), query] as const,
  reservation: (storeId: string, reservationId: string) =>
    [...storeOperatorKeys.reservations(storeId), reservationId] as const,
  pickups: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'pickup-reservations'] as const,
  pickupPages: (storeId: string) =>
    [...storeOperatorKeys.pickups(storeId), 'page'] as const,
  pickupPage: (storeId: string, query: object) =>
    [...storeOperatorKeys.pickupPages(storeId), query] as const,
  pickup: (storeId: string, pickupReservationId: string) =>
    [...storeOperatorKeys.pickups(storeId), pickupReservationId] as const,
  inventoryBuckets: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'menu-inventory-buckets'] as const,
  inventoryPage: (storeId: string, query: object) =>
    [...storeOperatorKeys.inventoryBuckets(storeId), 'page', query] as const,
  waitingSettings: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'waiting-settings'] as const,
  waitingDisableImpact: (storeId: string) =>
    [...storeOperatorKeys.waitingSettings(storeId), 'disable-impact'] as const,
  waitingClosureJob: (storeId: string, jobId: string) =>
    [...storeOperatorKeys.waitingSettings(storeId), 'closure-job', jobId] as const,
  waitingTeams: (storeId: string) =>
    [...storeOperatorKeys.store(storeId), 'waiting-teams'] as const,
  waitingTeamPages: (storeId: string) =>
    [...storeOperatorKeys.waitingTeams(storeId), 'page'] as const,
  waitingTeamPage: (storeId: string, query: object) =>
    [...storeOperatorKeys.waitingTeamPages(storeId), query] as const,
  waitingTeam: (storeId: string, waitingTeamId: string) =>
    [...storeOperatorKeys.waitingTeams(storeId), waitingTeamId] as const,
}
