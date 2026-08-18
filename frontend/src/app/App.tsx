import { Suspense, lazy } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Outlet, Route, Routes } from 'react-router'
import { createQueryClient } from '../shared/api/queryClient'
import { Loading } from '../shared/ui/Feedback'
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
 * 운영 콘솔은 동적 import로만 들어온다.
 *
 * 조건을 다른 모듈의 상수로 빼지 않고 `import.meta.env`를 여기서 직접 읽는다.
 * Vite가 이 표현식을 변환 시점에 literal로 바꾸므로, 꺼진 빌드에서는
 * `'' === 'true'`가 되어 삼항이 접히고 `import()`가 통째로 사라진다.
 * 상수를 다른 파일에서 가져오면 그 접기가 chunk 분할 뒤에 일어나, 실제로
 * `PlatformOperatorConsole` chunk가 산출물에 그대로 남는 것을 확인했다.
 *
 * 정적 import 후 런타임에 감추는 방식도 같은 이유로 쓰지 않는다.
 * 주소를 아는 사람이 콘솔 코드를 받아 갈 수 있다.
 */
const PlatformOperatorConsole =
  import.meta.env.VITE_PLATFORM_OPERATOR_ENABLED === 'true'
    ? lazy(
        () =>
          import(
            '../features/platform-operator-console/PlatformOperatorConsole'
          ),
      )
    : null

/**
 * 공용 route 그룹의 레이아웃.
 *
 * shell을 route 그룹으로 고정하면 로그인한 사용자가 공용 화면(`/`, `/stores` 등)에
 * 머무는 동안 주 메뉴·하단 탭·푸터가 전부 비로그인 화면처럼 보인다. 로그인 직후
 * 도착하는 곳이 `/`라서 사실상 로그인한 내내 그렇게 보였다. shell은 경로가 아니라
 * 인증 상태로 정한다.
 *
 * 결정은 여기서 하고 `AppLayout`에는 결과만 넘긴다. 레이아웃이 특정 shell의 인증
 * 상태를 직접 읽기 시작하면 매장 운영자 shell과 얽힌다.
 *
 * 복구 중에는 public으로 둔다. 결과가 나오기 전에 consumer 메뉴를 띄우면 비로그인
 * 사용자에게 잠깐 보였다가 사라진다.
 */
function ConsumerShellLayout() {
  const { status } = useConsumerAuth()

  return (
    <AppLayout
      shell={status === 'authenticated' ? 'consumer' : 'public'}
      accountSlot={<ConsumerAccountMenu />}
    />
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
              <Route element={<ConsumerShellLayout />}>
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

            {/*
              플랫폼 운영자 셸. `/admin/*` route context 아래에서 콘솔의 상대
              route를 매칭하며, 소비자·매장 운영자 provider 바깥에 둔다.
            */}
            {PlatformOperatorConsole !== null && (
              <Route
                path="/admin/*"
                element={
                  <Suspense
                    fallback={<Loading label="운영 콘솔을 여는 중입니다." />}
                  >
                    <PlatformOperatorConsole />
                  </Suspense>
                }
              />
            )}
          </Routes>
        </BrowserRouter>
      </QueryClientProvider>
    </AppErrorBoundary>
  )
}
