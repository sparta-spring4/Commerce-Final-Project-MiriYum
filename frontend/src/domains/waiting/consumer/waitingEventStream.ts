import {
  createChangedEventStreamClient,
  type ChangedEventConnectionState,
  type ChangedEventStreamClient,
} from '../../../shared/api/changedEventStream'
import type { ApiError } from '../../../shared/api/apiError'

export type WaitingEventConnectionState = ChangedEventConnectionState
export type WaitingEventStreamClient = ChangedEventStreamClient

export function createWaitingEventStreamClient(dependencies: {
  getAccessToken: () => string | null
  onUnauthorized: (error: ApiError) => Promise<boolean>
}): WaitingEventStreamClient {
  return createChangedEventStreamClient(dependencies, {
    path: '/api/v1/consumers/me/waiting-events',
    eventName: 'waiting.changed',
    invalidResponseMessage: '웨이팅 변경 응답이 SSE 계약과 다릅니다.',
  })
}
