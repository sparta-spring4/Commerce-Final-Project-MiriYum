import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router'
import { createQueryClient } from '../shared/api/queryClient'
import {
  ConsumerAccountMenu,
  ConsumerAuthProvider,
  ConsumerSignInPage,
  ConsumerSignUpPage,
  RequireConsumerAuth,
} from '../features/auth'
import { MyPage, MyReservationsPage } from '../features/consumer-account'
import {
  PickupCreatePage,
  PickupDetailPage,
} from '../features/pickup-reservations'
import {
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
                    path={ROUTES.pickupCreate}
                    element={<PickupCreatePage />}
                  />
                  <Route
                    path={ROUTES.pickupDetail}
                    element={<PickupDetailPage />}
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
