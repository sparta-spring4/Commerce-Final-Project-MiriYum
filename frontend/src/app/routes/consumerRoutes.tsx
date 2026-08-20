import { Route } from 'react-router'
import {
  ConsumerKakaoCallbackPage,
  ConsumerKakaoSignUpPage,
  ConsumerSignInPage,
  ConsumerSignUpPage,
} from '../../domains/account/consumer/auth'
import { MyPage } from '../../domains/account/consumer/profile'
import { NotificationHistoryPage } from '../../domains/notification/consumer/NotificationHistoryPage'
import {
  PickupCompletePage,
  PickupCreatePage,
  PickupDetailPage,
} from '../../domains/pickup/consumer'
import {
  ReservationCompletePage,
  ReservationCreatePage,
  ReservationDetailPage,
  MyReservationsPage,
} from '../../domains/reservation/consumer'
import {
  WaitingInvitationAcceptRoute,
  WaitingRegistrationRoute,
} from '../../domains/waiting/consumer'
import { ConsumerShell } from '../shells/consumer/ConsumerShell'
import { RequireConsumerAuth } from '../shells/consumer/RequireConsumerAuth'
import { useConsumerAuth } from '../shells/consumer/ConsumerAuthProvider'
import { CONSUMER_PATHS } from './paths/consumerPaths'

function ConsumerNotificationHistoryRoute() {
  const { apiClient, sessionKey } = useConsumerAuth()
  return <NotificationHistoryPage apiClient={apiClient} sessionKey={sessionKey} />
}

export const consumerEntryRoutes = [
  <Route key="consumer-sign-in" path={CONSUMER_PATHS.signIn} element={<ConsumerSignInPage />} />,
  <Route key="consumer-sign-up" path={CONSUMER_PATHS.signUp} element={<ConsumerSignUpPage />} />,
  <Route
    key="consumer-kakao-callback"
    path={CONSUMER_PATHS.kakaoCallback}
    element={<ConsumerKakaoCallbackPage />}
  />,
  <Route
    key="consumer-kakao-sign-up"
    path={CONSUMER_PATHS.kakaoSignUp}
    element={<ConsumerKakaoSignUpPage />}
  />,
]

export const consumerRoutes = (
  <Route element={<ConsumerShell />}>
    <Route element={<RequireConsumerAuth />}>
      <Route path={CONSUMER_PATHS.myPage} element={<MyPage />} />
      <Route path={CONSUMER_PATHS.myReservations} element={<MyReservationsPage />} />
      <Route
        path={CONSUMER_PATHS.notificationHistory}
        element={<ConsumerNotificationHistoryRoute />}
      />
      <Route path={CONSUMER_PATHS.reservationCreate} element={<ReservationCreatePage />} />
      <Route path={CONSUMER_PATHS.reservationDetail} element={<ReservationDetailPage />} />
      <Route path={CONSUMER_PATHS.reservationComplete} element={<ReservationCompletePage />} />
      <Route path={CONSUMER_PATHS.pickupCreate} element={<PickupCreatePage />} />
      <Route path={CONSUMER_PATHS.pickupDetail} element={<PickupDetailPage />} />
      <Route path={CONSUMER_PATHS.pickupComplete} element={<PickupCompletePage />} />
      <Route
        path={CONSUMER_PATHS.waitingInvitationAccept}
        element={<WaitingInvitationAcceptRoute />}
      />
      <Route
        path={CONSUMER_PATHS.waitingRegister}
        element={<WaitingRegistrationRoute />}
      />
    </Route>
  </Route>
)
