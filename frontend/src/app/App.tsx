import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router'
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
            </Routes>
          </ConsumerAuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </AppErrorBoundary>
  )
}
