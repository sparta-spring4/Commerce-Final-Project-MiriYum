export const notificationQueryKeys = {
  historyRoot: ['consumer', 'notification-history'] as const,
  unreadCount: (sessionKey: number) =>
    ['consumer', 'notification-history', 'unread-count', sessionKey] as const,
  history: (sessionKey: number) =>
    ['consumer', 'notification-history', sessionKey] as const,
}
