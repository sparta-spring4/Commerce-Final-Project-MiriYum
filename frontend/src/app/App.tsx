import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Outlet, Route, Routes } from 'react-router'
import { createQueryClient } from '../shared/api/queryClient'
import {
  ConsumerAccountMenu,
  ConsumerAuthProvider,
  ConsumerKakaoCallbackPage,
  ConsumerKakaoSignUpPage,
  ConsumerSignInPage,
  ConsumerSignUpPage,
  RequireConsumerAuth,
  useConsumerAuth,
} from '../features/auth'
import { MyPage, MyReservationsPage } from '../features/consumer-account'
import { NotificationHistoryPage } from '../features/notification-history/NotificationHistoryPage'
import {
  PickupCompletePage,
  PickupCreatePage,
  PickupDetailPage,
} from '../features/pickup-reservations'
import {
  ReservationCompletePage,
  ReservationCreatePage,
  ReservationDetailPage,
} from '../features/reservations'
import {
  ClosuresPage,
  CurrentStoreProvider,
  MenuEditorPage,
  MenuListPage,
  OperatingHoursPage,
  RequireStoreOperatorAuth,
  ReservationTimeSlotsPage,
  StoreCreatePage,
  StoreInfoPage,
  StoreOperatorAuthProvider,
  StoreOperatorHomePage,
  StoreOperatorLayout,
  StoreOperatorSignInPage,
  StoreOperatorSignUpPage,
} from '../features/store-operator'
import {
  WaitingSettingsPage,
  WaitingTeamDetailPage,
  WaitingTeamsPage,
} from '../features/store-waiting'
import {
  ReservationCapacityPage,
  ReservationTimePolicyPage,
  StoreReservationDetailPage,
  StoreReservationsPage,
} from '../features/store-reservation-ops'
import {
  HomePage,
  StoreDetailPage,
  StoreSearchPage,
} from '../features/store-search'
import { AppErrorBoundary } from './AppErrorBoundary'
import { AppLayout } from './AppLayout'
import { ForbiddenPage } from './ForbiddenPage'
import { NotFoundPage } from './NotFoundPage'
import { ROUTES } from './routes'

const queryClient = createQueryClient()

function ConsumerNotificationHistoryRoute() {
  const { apiClient, sessionKey } = useConsumerAuth()

  return (
    <NotificationHistoryPage
      apiClient={apiClient}
      sessionKey={sessionKey}
    />
  )
}

/**
 * 일반 사용자 셸의 provider 경계.
 *
 * layout route로 두는 이유는 provider가 실제로 mount되는 범위를 route 경계와
 * 같게 만들기 위해서다. `<Routes>` 바깥에서 감싸면 어떤 경로를 열든 provider가
 * 함께 mount되고, 이 provider는 mount 즉시 소비자 재발급을 호출한다. 그러면
 * 운영자 화면을 여는 것만으로 소비자 세션 복구와 캐시 정리가 돌아간다.
 */
function ConsumerShell() {
  return (
    <ConsumerAuthProvider>
      <Outlet />
    </ConsumerAuthProvider>
  )
}

/**
 * 매장 운영자 셸의 provider 경계.
 *
 * 인증 상태와 현재 매장 선택을 이 안에서만 들고 있다. 소비자 화면은 이 provider를
 * 지나가지 않으므로 두 셸의 상태가 섞이지 않는다.
 */
function StoreOperatorShell() {
  return (
    <StoreOperatorAuthProvider>
      <CurrentStoreProvider>
        <Outlet />
      </CurrentStoreProvider>
    </StoreOperatorAuthProvider>
  )
}

/**
 * 앱 셸. 화면 Issue는 routes.ts에 자기 route를 등록하고 여기에 element를 붙인다.
 * 1차 MVP에 없는 기능의 route는 만들지 않는다.
 *
 * 두 인증 shell은 형제 route 그룹이다. 한쪽 provider가 다른 쪽 route를 감싸지
 * 않으므로 토큰·쿠키·client뿐 아니라 mount 시점의 부수효과도 섞이지 않는다.
 */
export default function App() {
  return (
    <AppErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <Routes>
            {/* 일반 사용자 provider가 닿는 범위. 운영자 route는 이 밖에 있다. */}
            <Route element={<ConsumerShell />}>
              <Route
                element={
                  <AppLayout
                    shell="public"
                    accountSlot={<ConsumerAccountMenu />}
                  />
                }
              >
                <Route path={ROUTES.home} element={<HomePage />} />
                <Route path={ROUTES.stores} element={<StoreSearchPage />} />
                <Route path={ROUTES.storeDetail} element={<StoreDetailPage />} />
                <Route
                  path={ROUTES.consumerSignIn}
                  element={<ConsumerSignInPage />}
                />
                <Route
                  path={ROUTES.consumerSignUp}
                  element={<ConsumerSignUpPage />}
                />
                <Route
                  path={ROUTES.consumerKakaoCallback}
                  element={<ConsumerKakaoCallbackPage />}
                />
                <Route
                  path={ROUTES.consumerKakaoSignUp}
                  element={<ConsumerKakaoSignUpPage />}
                />
                <Route path={ROUTES.forbidden} element={<ForbiddenPage />} />
                <Route path="*" element={<NotFoundPage />} />
              </Route>

              {/* 일반 사용자 인증이 필요한 화면. 매장 운영자 화면은 여기 두지 않는다. */}
              <Route
                element={
                  <AppLayout
                    shell="consumer"
                    accountSlot={<ConsumerAccountMenu />}
                  />
                }
              >
                <Route element={<RequireConsumerAuth />}>
                  <Route path={ROUTES.myPage} element={<MyPage />} />
                  <Route
                    path={ROUTES.myReservations}
                    element={<MyReservationsPage />}
                  />
                  <Route
                    path={ROUTES.notificationHistory}
                    element={<ConsumerNotificationHistoryRoute />}
                  />
                  <Route
                    path={ROUTES.reservationCreate}
                    element={<ReservationCreatePage />}
                  />
                  <Route
                    path={ROUTES.reservationDetail}
                    element={<ReservationDetailPage />}
                  />
                  <Route
                    path={ROUTES.reservationComplete}
                    element={<ReservationCompletePage />}
                  />
                  <Route
                    path={ROUTES.pickupCreate}
                    element={<PickupCreatePage />}
                  />
                  <Route
                    path={ROUTES.pickupDetail}
                    element={<PickupDetailPage />}
                  />
                  <Route
                    path={ROUTES.pickupComplete}
                    element={<PickupCompletePage />}
                  />
                </Route>
              </Route>
            </Route>

            {/*
              매장 운영자 셸.

              일반 사용자 provider와 형제다. 운영자 경로를 열면 소비자 provider는
              아예 mount되지 않으므로 소비자 재발급 요청도 나가지 않는다.
            */}
            <Route element={<StoreOperatorShell />}>
                <Route
                  path={ROUTES.storeOperatorSignIn}
                  element={<StoreOperatorSignInPage />}
                />
                <Route
                  path={ROUTES.storeOperatorSignUp}
                  element={<StoreOperatorSignUpPage />}
                />

                <Route element={<RequireStoreOperatorAuth />}>
                  <Route element={<StoreOperatorLayout />}>
                    <Route
                      path={ROUTES.storeOperatorHome}
                      element={<StoreOperatorHomePage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorStoreCreate}
                      element={<StoreCreatePage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorStore}
                      element={<StoreInfoPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorOperatingHours}
                      element={<OperatingHoursPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorReservationTimeSlots}
                      element={<ReservationTimeSlotsPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorClosures}
                      element={<ClosuresPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorMenus}
                      element={<MenuListPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorMenuCreate}
                      element={<MenuEditorPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorMenu}
                      element={<MenuEditorPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorReservationCapacities}
                      element={<ReservationCapacityPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorReservationTimePolicy}
                      element={<ReservationTimePolicyPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorReservations}
                      element={<StoreReservationsPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorReservation}
                      element={<StoreReservationDetailPage />}
                    />
                    {/*
                      웨이팅 고도화(#411). 실시간 구독(#250)은 계약이 없어
                      목록·상세는 중앙 snapshot 재조회로만 갱신한다.
                    */}
                    <Route
                      path={ROUTES.storeOperatorWaitingSettings}
                      element={<WaitingSettingsPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorWaitingTeams}
                      element={<WaitingTeamsPage />}
                    />
                    <Route
                      path={ROUTES.storeOperatorWaitingTeam}
                      element={<WaitingTeamDetailPage />}
                    />
                  </Route>
                </Route>
              </Route>
          </Routes>
        </BrowserRouter>
      </QueryClientProvider>
    </AppErrorBoundary>
  )
}
