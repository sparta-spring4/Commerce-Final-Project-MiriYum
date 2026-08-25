import { Link } from 'react-router'

import { useNotificationCenter } from '../../../domains/notification/consumer/NotificationCenterProvider'
import { Icon } from '../../../shared/ui/Icon'
import { CONSUMER_PATHS } from '../../routes/paths/consumerPaths'
import { ConsumerAccountMenu } from './ConsumerAccountMenu'

export function ConsumerHeaderActions({ showNotifications = true }: { showNotifications?: boolean }) {
  const { unreadCount, isUnreadCountError } = useNotificationCenter()
  const accessibleName = isUnreadCountError
    ? '알림, 읽지 않은 알림 개수를 확인할 수 없음'
    : unreadCount !== null && unreadCount > 0
      ? `알림, 읽지 않은 알림 ${unreadCount}개`
      : '알림'

  return (
    <div className="app-header__actions">
      {showNotifications ? <Link className="app-header__notification" to={CONSUMER_PATHS.notificationHistory} aria-label={accessibleName}>
        <Icon name="bell" />
        {isUnreadCountError ? (
          <span className="app-header__notification-badge" aria-hidden="true">!</span>
        ) : unreadCount !== null && unreadCount > 0 ? (
          <span className="app-header__notification-badge" data-testid="notification-badge" aria-hidden="true">
            {unreadCount >= 100 ? '99+' : unreadCount}
          </span>
        ) : null}
      </Link> : null}
      <ConsumerAccountMenu />
    </div>
  )
}
