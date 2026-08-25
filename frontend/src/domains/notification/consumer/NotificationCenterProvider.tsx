import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

import type { ApiClient } from '../../../shared/api/client'
import { readAllNotifications, readNotification, readNotificationUnreadCount } from './notificationCenterApi'
import type { NotificationEventStreamClient } from './notificationEventStream'
import type { NotificationEventConnectionState } from './notificationEventStream'
import { notificationQueryKeys } from './notificationQueryKeys'

export type NotificationCenterContextValue = {
  unreadCount: number | null
  isUnreadCountError: boolean
  connectionState: NotificationEventConnectionState | null
  markRead: (notificationId: string) => Promise<void>
  markAllRead: () => Promise<void>
}

export const NotificationCenterContext = createContext<NotificationCenterContextValue | null>(null)

export function useNotificationCenter(): NotificationCenterContextValue {
  const value = useContext(NotificationCenterContext)
  if (value === null) {
    throw new Error('useNotificationCenter는 NotificationCenterProvider 안에서만 사용할 수 있습니다.')
  }
  return value
}

type Props = {
  apiClient: ApiClient
  eventStream: NotificationEventStreamClient
  sessionKey: number
  enabled?: boolean
  children: ReactNode
}

export function NotificationCenterProvider({ apiClient, eventStream, sessionKey, enabled = true, children }: Props) {
  const queryClient = useQueryClient()
  const [connectionState, setConnectionState] = useState<NotificationEventConnectionState | null>(null)
  const unreadKey = useMemo(() => notificationQueryKeys.unreadCount(sessionKey), [sessionKey])
  const historyKey = useMemo(() => notificationQueryKeys.history(sessionKey), [sessionKey])
  const unread = useQuery({
    queryKey: unreadKey,
    queryFn: ({ signal }) => readNotificationUnreadCount(apiClient, signal),
    enabled,
  })

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    let refreshRequested = false
    let refreshRunning = false

    const refreshQueries = async () => {
      if (refreshRunning) return
      refreshRunning = true
      try {
        while (refreshRequested && !controller.signal.aborted) {
          refreshRequested = false
          const queryKeys = [historyKey, unreadKey]
          for (const queryKey of queryKeys) {
            const state = queryClient.getQueryState(queryKey)
            if (state?.fetchStatus === 'fetching' && state.data === undefined) {
              await queryClient.cancelQueries({ queryKey, exact: true })
            }
          }
          // 취소 중 합쳐진 신호는 지금 시작할 재조회가 함께 회수한다.
          refreshRequested = false
          for (const queryKey of queryKeys) {
            if (!controller.signal.aborted) {
              await queryClient.invalidateQueries({ queryKey, exact: true })
            }
          }
        }
      } finally {
        refreshRunning = false
      }
    }

    void eventStream.subscribe({
      signal: controller.signal,
      onChanged: () => {
        refreshRequested = true
        void refreshQueries()
      },
      onConnectionStateChange: setConnectionState,
    })
    return () => {
      refreshRequested = false
      controller.abort()
    }
  }, [enabled, eventStream, historyKey, queryClient, unreadKey])

  const value = useMemo<NotificationCenterContextValue>(() => {
    const refreshAfterRead = async () => {
      await queryClient.invalidateQueries({ queryKey: unreadKey, exact: true })
      await queryClient.invalidateQueries({ queryKey: historyKey, exact: true })
    }
    return {
      unreadCount: unread.data ?? null,
      isUnreadCountError: unread.isError,
      connectionState,
      markRead: async (notificationId) => {
        await readNotification(apiClient, notificationId)
        await refreshAfterRead()
      },
      markAllRead: async () => {
        await readAllNotifications(apiClient)
        await refreshAfterRead()
      },
    }
  }, [apiClient, connectionState, historyKey, queryClient, unread.data, unread.isError, unreadKey])

  return <NotificationCenterContext.Provider value={value}>{children}</NotificationCenterContext.Provider>
}
