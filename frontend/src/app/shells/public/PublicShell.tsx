import { AppLayout } from '../shared/AppLayout'
import { ConsumerAccountMenu } from '../consumer/ConsumerAccountMenu'
import { useConsumerAuth } from '../consumer/ConsumerAuthProvider'
import { CONSUMER_NAVIGATION } from '../consumer/navigation'
import { PUBLIC_NAVIGATION } from './navigation'

export function PublicShell() {
  const { status } = useConsumerAuth()

  return (
    <AppLayout
      isConsumer={status === 'authenticated'}
      navigation={
        status === 'authenticated' ? CONSUMER_NAVIGATION : PUBLIC_NAVIGATION
      }
      accountSlot={<ConsumerAccountMenu />}
    />
  )
}
