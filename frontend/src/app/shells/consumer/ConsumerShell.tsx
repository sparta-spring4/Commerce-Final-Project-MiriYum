import { AppLayout } from '../shared/AppLayout'
import { ConsumerAccountMenu } from './ConsumerAccountMenu'
import { CONSUMER_NAVIGATION } from './navigation'

export function ConsumerShell() {
  return (
    <AppLayout
      isConsumer
      navigation={CONSUMER_NAVIGATION}
      accountSlot={<ConsumerAccountMenu />}
    />
  )
}
