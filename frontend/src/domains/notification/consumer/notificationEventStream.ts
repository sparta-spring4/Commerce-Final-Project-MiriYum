import { ApiError } from '../../../shared/api/apiError'
import { isApiErrorBody } from '../../../shared/api/envelope'

const NOTIFICATION_EVENT_PATH =
  '/api/v1/consumers/me/notification-events' as const
const NOTIFICATION_CHANGED_EVENT = 'notifications.changed'
const RETRY_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 10_000] as const
const STABLE_CONNECTION_MS = 30_000

export type NotificationEventConnectionState =
  | 'connected'
  | 'reconnecting'
  | 'unavailable'

type ParseNotificationChangedEventsOptions = {
  signal: AbortSignal
  onChanged: (cursor: string) => void
}

type NotificationEventSubscription = {
  signal: AbortSignal
  onChanged: () => void
  onConnectionStateChange: (state: NotificationEventConnectionState) => void
}

export type NotificationEventStreamClient = {
  subscribe: (subscription: NotificationEventSubscription) => Promise<void>
}

type NotificationEventStreamDependencies = {
  getAccessToken: () => string | null
  onUnauthorized: (error: ApiError) => Promise<boolean>
  fetcher?: typeof fetch
  waitForReconnect?: (delayMs: number, signal: AbortSignal) => Promise<void>
  now?: () => number
}

function readField(line: string): { name: string; value: string } | null {
  if (line.length === 0 || line.startsWith(':')) {
    return null
  }
  const separator = line.indexOf(':')
  if (separator === -1) {
    return { name: line, value: '' }
  }
  const rawValue = line.slice(separator + 1)
  return {
    name: line.slice(0, separator),
    value: rawValue.startsWith(' ') ? rawValue.slice(1) : rawValue,
  }
}

function consumeFrame(
  frame: string,
  onChanged: (cursor: string) => void,
): void {
  let eventName = ''
  let cursor: string | null = null
  const dataLines: string[] = []

  for (const line of frame.split(/\r?\n/)) {
    const field = readField(line)
    if (field === null) {
      continue
    }
    if (field.name === 'event') {
      eventName = field.value
    } else if (field.name === 'id' && !field.value.includes('\0')) {
      cursor = field.value
    } else if (field.name === 'data') {
      dataLines.push(field.value)
    }
  }

  if (
    eventName === NOTIFICATION_CHANGED_EVENT &&
    cursor !== null &&
    cursor.length > 0 &&
    dataLines.join('\n') === '{}'
  ) {
    onChanged(cursor)
  }
}

/**
 * UTF-8 SSE wire frame을 완성된 빈 줄 경계에서만 처리한다.
 *
 * cursor는 해석하지 않고 changed callback에 그대로 넘긴다. keepalive comment와
 * 알 수 없는 event는 업무 신호가 아니므로 cursor도 전진시키지 않는다.
 */
export async function parseNotificationChangedEvents(
  stream: ReadableStream<Uint8Array>,
  options: ParseNotificationChangedEventsOptions,
): Promise<void> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const cancel = () => {
    void reader.cancel()
  }

  options.signal.addEventListener('abort', cancel, { once: true })
  try {
    while (!options.signal.aborted) {
      const result = await reader.read()
      if (result.done) {
        break
      }
      if (options.signal.aborted) {
        break
      }
      buffer += decoder.decode(result.value, { stream: true })

      let boundary = /\r?\n\r?\n/.exec(buffer)
      while (boundary !== null) {
        const frame = buffer.slice(0, boundary.index)
        buffer = buffer.slice(boundary.index + boundary[0].length)
        consumeFrame(frame, options.onChanged)
        if (options.signal.aborted) {
          break
        }
        boundary = /\r?\n\r?\n/.exec(buffer)
      }
    }
  } finally {
    options.signal.removeEventListener('abort', cancel)
    reader.releaseLock()
  }
}

async function waitForReconnect(
  delayMs: number,
  signal: AbortSignal,
): Promise<void> {
  if (signal.aborted) {
    return
  }
  await new Promise<void>((resolve) => {
    let settled = false
    let timeout = 0
    const finish = () => {
      if (settled) {
        return
      }
      settled = true
      window.clearTimeout(timeout)
      signal.removeEventListener('abort', finish)
      resolve()
    }
    timeout = window.setTimeout(finish, delayMs)
    signal.addEventListener('abort', finish, { once: true })
    if (signal.aborted) {
      finish()
    }
  })
}

async function toApiError(response: Response): Promise<ApiError> {
  let payload: unknown
  try {
    payload = JSON.parse(await response.text())
  } catch {
    payload = undefined
  }
  if (isApiErrorBody(payload)) {
    return new ApiError({
      status: response.status,
      code: payload.code,
      message: payload.message,
      details: payload.details,
    })
  }
  return new ApiError({
    status: response.status,
    code: `HTTP_${response.status}`,
    message: '알림 변경 연결을 확인할 수 없습니다.',
  })
}

function retryDelay(attempt: number): number {
  return RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)]
}

function isEventStreamContentType(contentType: string | null): boolean {
  return contentType?.split(';', 1)[0]?.trim().toLowerCase() === 'text/event-stream'
}

/**
 * consumer shell이 주입한 메모리 token·재발급 경계로만 stream을 연다.
 *
 * 연결 신호는 상태가 아니므로 callback에는 payload를 노출하지 않는다. 유효한
 * changed frame의 opaque id만 같은 구독 생명주기 안에서 재연결에 사용한다.
 */
export function createNotificationEventStreamClient(
  dependencies: NotificationEventStreamDependencies,
): NotificationEventStreamClient {
  const fetcher = dependencies.fetcher ?? fetch
  const sleep = dependencies.waitForReconnect ?? waitForReconnect
  const now = dependencies.now ?? (() => performance.now())

  async function send(
    cursor: string | undefined,
    signal: AbortSignal,
  ): Promise<Response> {
    const headers = new Headers({ Accept: 'text/event-stream' })
    const token = dependencies.getAccessToken()
    if (token !== null) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    if (cursor !== undefined) {
      headers.set('Last-Event-ID', cursor)
    }
    return fetcher(NOTIFICATION_EVENT_PATH, {
      method: 'GET',
      headers,
      signal,
      credentials: 'same-origin',
    })
  }

  async function open(
    cursor: string | undefined,
    signal: AbortSignal,
  ): Promise<Response> {
    let response = await send(cursor, signal)
    if (response.status === 401) {
      const error = await toApiError(response)
      if (await dependencies.onUnauthorized(error)) {
        response = await send(cursor, signal)
      } else {
        throw error
      }
    }
    if (!response.ok) {
      throw await toApiError(response)
    }
    if (
      response.body === null ||
      !isEventStreamContentType(response.headers.get('Content-Type'))
    ) {
      throw new ApiError({
        status: response.status,
        code: 'HTTP_200',
        message: '알림 변경 응답이 SSE 계약과 다릅니다.',
      })
    }
    return response
  }

  return {
    async subscribe(subscription) {
      let cursor: string | undefined
      let retryAttempt = 0

      while (!subscription.signal.aborted) {
        let connectedAt: number | null = null
        try {
          const response = await open(cursor, subscription.signal)
          if (subscription.signal.aborted) {
            await response.body?.cancel()
            return
          }
          connectedAt = now()
          subscription.onConnectionStateChange('connected')
          await parseNotificationChangedEvents(response.body!, {
            signal: subscription.signal,
            onChanged: (nextCursor) => {
              if (subscription.signal.aborted) {
                return
              }
              cursor = nextCursor
              subscription.onChanged()
            },
          })
          if (subscription.signal.aborted) {
            return
          }
        } catch (error) {
          if (subscription.signal.aborted) {
            return
          }
          if (
            error instanceof ApiError &&
            error.status === 400 &&
            error.code === 'COMMON_001' &&
            cursor !== undefined
          ) {
            cursor = undefined
            continue
          }
          const isTransient =
            error instanceof TypeError ||
            (error instanceof ApiError &&
              (error.status === 429 || error.status === 503))
          if (!isTransient) {
            subscription.onConnectionStateChange('unavailable')
            return
          }
        }

        if (
          connectedAt !== null &&
          now() - connectedAt >= STABLE_CONNECTION_MS
        ) {
          retryAttempt = 0
        }

        subscription.onConnectionStateChange('reconnecting')
        const delay = retryDelay(retryAttempt)
        retryAttempt += 1
        await sleep(delay, subscription.signal)
      }
    },
  }
}
