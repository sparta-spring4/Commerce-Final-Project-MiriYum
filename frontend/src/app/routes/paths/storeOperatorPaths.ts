export const STORE_OPERATOR_PATHS = {
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
  reservation: '/store-operator/stores/:storeId/reservations/:reservationId',
  waitingSettings: '/store-operator/stores/:storeId/waiting-settings',
  waitingTeams: '/store-operator/stores/:storeId/waiting-teams',
  waitingTeam: '/store-operator/stores/:storeId/waiting-teams/:waitingTeamId',
} as const
