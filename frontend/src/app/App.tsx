import { Suspense, lazy } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router'
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
              {/*
                운영 콘솔은 독립 셸이라 AppLayout·ConsumerAuthProvider의 화면
                구성을 쓰지 않는다. `/admin/*`를 통째로 넘겨 자기 provider와
                가드로 하위 route를 스스로 구성하게 한다.
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
            </Routes>
          </ConsumerAuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </AppErrorBoundary>
  )
}
