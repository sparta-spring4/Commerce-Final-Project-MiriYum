import {
  createChangedEventStreamClient,
  parseChangedEvents,
  type ChangedEventConnectionState,
  type ChangedEventStreamClient,
} from '../../../shared/api/changedEventStream'
import type { ApiError } from '../../../shared/api/apiError'

const NOTIFICATION_EVENT_PATH = '/api/v1/consumers/me/notification-events'
const NOTIFICATION_CHANGED_EVENT = 'notifications.changed'

export type NotificationEventConnectionState = ChangedEventConnectionState
export type NotificationEventStreamClient = ChangedEventStreamClient

type NotificationEventStreamDependencies = {
  getAccessToken: () => string | null
  onUnauthorized: (error: ApiError) => Promise<boolean>
  fetcher?: typeof fetch
  waitForReconnect?: (delayMs: number, signal: AbortSignal) => Promise<void>
  now?: () => number
}

export function parseNotificationChangedEvents(
  stream: ReadableStream<Uint8Array>,
  options: { signal: AbortSignal; onChanged: (cursor: string) => void },
): Promise<void> {
  return parseChangedEvents(stream, {
    ...options,
    eventName: NOTIFICATION_CHANGED_EVENT,
  })
}

export function createNotificationEventStreamClient(
  dependencies: NotificationEventStreamDependencies,
): NotificationEventStreamClient {
  return createChangedEventStreamClient(dependencies, {
    path: NOTIFICATION_EVENT_PATH,
    eventName: NOTIFICATION_CHANGED_EVENT,
    invalidResponseMessage: '알림 변경 응답이 SSE 계약과 다릅니다.',
  })
}
