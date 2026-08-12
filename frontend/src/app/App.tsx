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
} from '../features/auth'
import { MyPage, MyReservationsPage } from '../features/consumer-account'
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
 * 일반 사용자 인증 shell만 감싼다. 매장 운영자 shell은 자기 provider와 가드로
 * 별도 route 그룹을 만든다. 두 shell의 상태를 한 provider로 합치지 않는다.
 */
export default function App() {
  return (
    <AppErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <ConsumerAuthProvider>
            <Routes>
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

              {/*
                매장 운영자 셸.

                일반 사용자 provider 밖에 두지 않는 이유는 라우터가 하나이기
                때문이며, 인증 상태·토큰·쿠키는 이 안의 별도 provider가 소유한다.
                두 셸은 서로의 상태를 읽지 않는다.
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
                  </Route>
                </Route>
              </Route>
            </Routes>
          </ConsumerAuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </AppErrorBoundary>
  )
}
