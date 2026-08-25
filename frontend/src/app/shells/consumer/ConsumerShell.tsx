import { AppLayout } from '../shared/AppLayout'
import { NotificationCenterProvider } from '../../../domains/notification/consumer/NotificationCenterProvider'
import { ConsumerHeaderActions } from './ConsumerHeaderActions'
import { useConsumerAuth } from './ConsumerAuthProvider'
import { CONSUMER_NAVIGATION } from './navigation'

export function ConsumerShell() {
  const { apiClient, notificationEventStream, sessionKey, status } = useConsumerAuth()
  return (
    <NotificationCenterProvider
      key={sessionKey}
      apiClient={apiClient}
      eventStream={notificationEventStream}
      sessionKey={sessionKey}
      enabled={status === 'authenticated'}
    >
      <AppLayout
        isConsumer
        navigation={CONSUMER_NAVIGATION}
        accountSlot={<ConsumerHeaderActions showNotifications={status === 'authenticated'} />}
      />
    </NotificationCenterProvider>
  )
}
