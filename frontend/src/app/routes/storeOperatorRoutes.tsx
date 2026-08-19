import { Route } from 'react-router'
import {
  ClosuresPage,
  MenuEditorPage,
  MenuListPage,
  OperatingHoursPage,
  ReservationTimeSlotsPage,
  StoreCreatePage,
  StoreInfoPage,
  StoreOperatorHomePage,
} from '../../domains/store/store-operator'
import { StoreOperatorSignInPage } from '../../domains/account/store-operator/auth/ui/StoreOperatorSignInPage'
import { StoreOperatorSignUpPage } from '../../domains/account/store-operator/auth/ui/StoreOperatorSignUpPage'
import {
  ReservationCapacityPage,
  ReservationTimePolicyPage,
  StoreReservationDetailPage,
  StoreReservationsPage,
} from '../../domains/reservation/store-operator'
import {
  WaitingSettingsPage,
  WaitingTeamDetailPage,
  WaitingTeamsPage,
} from '../../domains/waiting/store-operator'
import { StoreOperatorBoundary } from '../shells/store-operator/StoreOperatorBoundary'
import { RequireStoreOperatorAuth } from '../shells/store-operator/RequireStoreOperatorAuth'
import { StoreOperatorLayout } from '../shells/store-operator/StoreOperatorLayout'
import { STORE_OPERATOR_PATHS } from './paths/storeOperatorPaths'

export const storeOperatorRoutes = (
  <Route element={<StoreOperatorBoundary />}>
    <Route path={STORE_OPERATOR_PATHS.signIn} element={<StoreOperatorSignInPage />} />
    <Route path={STORE_OPERATOR_PATHS.signUp} element={<StoreOperatorSignUpPage />} />
    <Route element={<RequireStoreOperatorAuth />}>
      <Route element={<StoreOperatorLayout />}>
        <Route path={STORE_OPERATOR_PATHS.home} element={<StoreOperatorHomePage />} />
        <Route path={STORE_OPERATOR_PATHS.storeCreate} element={<StoreCreatePage />} />
        <Route path={STORE_OPERATOR_PATHS.store} element={<StoreInfoPage />} />
        <Route path={STORE_OPERATOR_PATHS.operatingHours} element={<OperatingHoursPage />} />
        <Route path={STORE_OPERATOR_PATHS.reservationTimeSlots} element={<ReservationTimeSlotsPage />} />
        <Route path={STORE_OPERATOR_PATHS.closures} element={<ClosuresPage />} />
        <Route path={STORE_OPERATOR_PATHS.menus} element={<MenuListPage />} />
        <Route path={STORE_OPERATOR_PATHS.menuCreate} element={<MenuEditorPage />} />
        <Route path={STORE_OPERATOR_PATHS.menu} element={<MenuEditorPage />} />
        <Route path={STORE_OPERATOR_PATHS.reservationCapacities} element={<ReservationCapacityPage />} />
        <Route path={STORE_OPERATOR_PATHS.reservationTimePolicy} element={<ReservationTimePolicyPage />} />
        <Route path={STORE_OPERATOR_PATHS.reservations} element={<StoreReservationsPage />} />
        <Route path={STORE_OPERATOR_PATHS.reservation} element={<StoreReservationDetailPage />} />
        <Route path={STORE_OPERATOR_PATHS.waitingSettings} element={<WaitingSettingsPage />} />
        <Route path={STORE_OPERATOR_PATHS.waitingTeams} element={<WaitingTeamsPage />} />
        <Route path={STORE_OPERATOR_PATHS.waitingTeam} element={<WaitingTeamDetailPage />} />
      </Route>
    </Route>
  </Route>
)
