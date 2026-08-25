import { AppLayout } from '../shared/AppLayout'
import { NotificationCenterProvider } from '../../../domains/notification/consumer/NotificationCenterProvider'
import { ConsumerHeaderActions } from '../consumer/ConsumerHeaderActions'
import { useConsumerAuth } from '../consumer/ConsumerAuthProvider'
import { CONSUMER_NAVIGATION } from '../consumer/navigation'
import { PUBLIC_NAVIGATION } from './navigation'

export function PublicShell() {
  const { apiClient, notificationEventStream, sessionKey, status } =
    useConsumerAuth()

  return (
    <NotificationCenterProvider
      apiClient={apiClient}
      eventStream={notificationEventStream}
      sessionKey={sessionKey}
      enabled={status === 'authenticated'}
    >
      <AppLayout
        isConsumer={status === 'authenticated'}
        navigation={
          status === 'authenticated' ? CONSUMER_NAVIGATION : PUBLIC_NAVIGATION
        }
        accountSlot={
          <ConsumerHeaderActions showNotifications={status === 'authenticated'} />
        }
      />
    </NotificationCenterProvider>
  )
}
