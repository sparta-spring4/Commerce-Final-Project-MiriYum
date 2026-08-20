import { describe, expect, test, vi } from 'vitest'

import { AuthErrorCode } from '../../../shared/auth/authErrors'
import {
  createNotificationEventStreamClient,
  parseNotificationChangedEvents,
} from './notificationEventStream'

const EVENT_PATH = '/api/v1/consumers/me/notification-events'
const encoder = new TextEncoder()

function eventStream(chunks: string[]): Response {
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(encoder.encode(chunk))
        }
        controller.close()
      },
    }),
    {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream; charset=UTF-8' },
    },
  )
}

function endlessEventStream(chunk: string): Response {
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(chunk))
      },
    }),
    {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    },
  )
}

function header(init: RequestInit | undefined, name: string): string | null {
  return new Headers(init?.headers).get(name)
}

describe('notification event stream parser', () => {
  test('emits complete changed frames once across chunk and line-ending boundaries', async () => {
    const cursors: string[] = []
    const stream = eventStream([
      ': heartbeat\r',
      '\n\r\nid: opaque_A-1\r\nevent: notifications.',
      'changed\r\ndata: {}\r\n\r\nevent: ignored.event\ndata: {}\n\n',
      'id: opaque_B_2\nevent: notifications.changed\ndata: {}\n\n',
    ]).body

    if (stream === null) {
      throw new Error('테스트 stream body가 필요합니다.')
    }

    await parseNotificationChangedEvents(stream, {
      signal: new AbortController().signal,
      onChanged: (cursor) => cursors.push(cursor),
    })

    expect(cursors).toEqual(['opaque_A-1', 'opaque_B_2'])
  })

  test('ignores keepalive, unknown events, and malformed changed data', async () => {
    const cursors: string[] = []
    const stream = eventStream([
      ': keepalive\n\n',
      'id: unknown\nevent: something.changed\ndata: {}\n\n',
      'id: malformed\nevent: notifications.changed\ndata: {"state":"old"}\n\n',
      'event: notifications.changed\ndata: {}\n\n',
    ]).body

    if (stream === null) {
      throw new Error('테스트 stream body가 필요합니다.')
    }

    await parseNotificationChangedEvents(stream, {
      signal: new AbortController().signal,
      onChanged: (cursor) => cursors.push(cursor),
    })

    expect(cursors).toEqual([])
  })

  test('does not emit a completed frame when abort wins after a pending read', async () => {
    const cursors: string[] = []
    const controller = new AbortController()
    let streamController!: ReadableStreamDefaultController<Uint8Array>
    const stream = new ReadableStream<Uint8Array>({
      start(nextController) {
        streamController = nextController
      },
    })
    const parsing = parseNotificationChangedEvents(stream, {
      signal: controller.signal,
      onChanged: (cursor) => cursors.push(cursor),
    })

    streamController.enqueue(
      encoder.encode(
        'id: late_frame\nevent: notifications.changed\ndata: {}\n\n',
      ),
    )
    controller.abort()
    await parsing

    expect(cursors).toEqual([])
  })
})

describe('notification event stream client', () => {
  test('opens an authenticated fetch stream and emits a changed signal', async () => {
    const requests: Array<{ input: RequestInfo | URL; init?: RequestInit }> = []
    const controller = new AbortController()
    let changed = 0
    const fetcher = async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ): Promise<Response> => {
      requests.push({ input, init })
      return endlessEventStream(
        'id: cursor_1\nevent: notifications.changed\ndata: {}\n\n',
      )
    }
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'consumer-token',
      onUnauthorized: async () => false,
      fetcher,
    })

    await client.subscribe({
      signal: controller.signal,
      onChanged: () => {
        changed += 1
        controller.abort()
      },
      onConnectionStateChange: () => {},
    })

    expect(changed).toBe(1)
    expect(requests).toHaveLength(1)
    expect(requests[0]?.input).toBe(EVENT_PATH)
    expect(header(requests[0]?.init, 'Authorization')).toBe(
      'Bearer consumer-token',
    )
    expect(header(requests[0]?.init, 'Accept')).toBe('text/event-stream')
    expect(header(requests[0]?.init, 'Last-Event-ID')).toBeNull()
    expect(requests[0]?.init?.credentials).toBe('same-origin')
  })

  test('reuses the opaque event id unchanged only after a reconnect', async () => {
    const cursors: Array<string | null> = []
    const delays: number[] = []
    const controller = new AbortController()
    let calls = 0
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'token',
      onUnauthorized: async () => false,
      fetcher: async (_input, init) => {
        cursors.push(header(init, 'Last-Event-ID'))
        calls += 1
        if (calls === 1) {
          return eventStream([
            'id: v1_AbC-123_XyZ\nevent: notifications.changed\ndata: {}\n\n',
          ])
        }
        return endlessEventStream(
          'id: next_cursor\nevent: notifications.changed\ndata: {}\n\n',
        )
      },
      waitForReconnect: async (delay) => {
        delays.push(delay)
      },
    })

    await client.subscribe({
      signal: controller.signal,
      onChanged: () => {
        if (calls === 2) {
          controller.abort()
        }
      },
      onConnectionStateChange: () => {},
    })

    expect(cursors).toEqual([null, 'v1_AbC-123_XyZ'])
    expect(delays).toEqual([1_000])
  })

  test('refreshes one expired access token and retries with the new token', async () => {
    const authorizations: Array<string | null> = []
    const controller = new AbortController()
    let token = 'expired-token'
    let unauthorizedCode: string | null = null
    let calls = 0
    const client = createNotificationEventStreamClient({
      getAccessToken: () => token,
      onUnauthorized: async (error) => {
        unauthorizedCode = error.code
        token = 'fresh-token'
        return true
      },
      fetcher: async (_input, init) => {
        authorizations.push(header(init, 'Authorization'))
        calls += 1
        if (calls === 1) {
          return new Response(
            JSON.stringify({
              code: AuthErrorCode.ACCESS_TOKEN_EXPIRED,
              message: 'Access Token이 만료됐습니다.',
            }),
            { status: 401, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return endlessEventStream(
          'id: refreshed\nevent: notifications.changed\ndata: {}\n\n',
        )
      },
    })

    await client.subscribe({
      signal: controller.signal,
      onChanged: () => controller.abort(),
      onConnectionStateChange: () => {},
    })

    expect(unauthorizedCode).toBe(AuthErrorCode.ACCESS_TOKEN_EXPIRED)
    expect(authorizations).toEqual([
      'Bearer expired-token',
      'Bearer fresh-token',
    ])
  })

  test('drops one invalid reconnect cursor and recovers with an initial connection', async () => {
    const cursors: Array<string | null> = []
    const controller = new AbortController()
    let calls = 0
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'token',
      onUnauthorized: async () => false,
      fetcher: async (_input, init) => {
        const cursor = header(init, 'Last-Event-ID')
        cursors.push(cursor)
        calls += 1
        if (calls === 1) {
          return eventStream([
            'id: stale_cursor\nevent: notifications.changed\ndata: {}\n\n',
          ])
        }
        if (calls === 2) {
          return new Response(
            JSON.stringify({
              code: 'COMMON_001',
              message: '입력값이 올바르지 않습니다.',
            }),
            { status: 400, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return endlessEventStream(
          'id: recovered\nevent: notifications.changed\ndata: {}\n\n',
        )
      },
      waitForReconnect: async () => {},
    })

    await client.subscribe({
      signal: controller.signal,
      onChanged: () => {
        if (calls === 3) {
          controller.abort()
        }
      },
      onConnectionStateChange: () => {},
    })

    expect(cursors).toEqual([null, 'stale_cursor', null])
  })

  test('caps transient reconnect backoff instead of growing without bound', async () => {
    const delays: number[] = []
    const controller = new AbortController()
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'token',
      onUnauthorized: async () => false,
      fetcher: async () => {
        throw new TypeError('network unavailable')
      },
      waitForReconnect: async (delay) => {
        delays.push(delay)
        if (delays.length === 5) {
          controller.abort()
        }
      },
    })

    await client.subscribe({
      signal: controller.signal,
      onChanged: () => {},
      onConnectionStateChange: () => {},
    })

    expect(delays).toEqual([1_000, 2_000, 4_000, 8_000, 10_000])
  })

  test('progresses capped backoff when accepted streams repeatedly close immediately', async () => {
    const delays: number[] = []
    const controller = new AbortController()
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'token',
      onUnauthorized: async () => false,
      fetcher: async () => eventStream([]),
      waitForReconnect: async (delay) => {
        delays.push(delay)
        if (delays.length === 5) {
          controller.abort()
        }
      },
    })

    await client.subscribe({
      signal: controller.signal,
      onChanged: () => {},
      onConnectionStateChange: () => {},
    })

    expect(delays).toEqual([1_000, 2_000, 4_000, 8_000, 10_000])
  })

  test('resets reconnect backoff after a stream remains stable for 30 seconds', async () => {
    const delays: number[] = []
    const controller = new AbortController()
    const times = [0, 30_000]
    let calls = 0
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'token',
      onUnauthorized: async () => false,
      fetcher: async () => {
        calls += 1
        if (calls === 1) {
          throw new TypeError('network unavailable')
        }
        return eventStream([])
      },
      waitForReconnect: async (delay) => {
        delays.push(delay)
        if (delays.length === 2) {
          controller.abort()
        }
      },
      now: () => times.shift() ?? 30_000,
    })

    await client.subscribe({
      signal: controller.signal,
      onChanged: () => {},
      onConnectionStateChange: () => {},
    })

    expect(delays).toEqual([1_000, 1_000])
  })

  test('does not publish state or changed callbacks after an in-flight open is aborted', async () => {
    const states: string[] = []
    let changed = 0
    let settleFetch: ((response: Response) => void) | undefined
    const fetchStarted = vi.fn()
    const controller = new AbortController()
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'token',
      onUnauthorized: async () => false,
      fetcher: async () => {
        fetchStarted()
        return new Promise<Response>((resolve) => {
          settleFetch = resolve
        })
      },
    })
    const running = client.subscribe({
      signal: controller.signal,
      onChanged: () => {
        changed += 1
      },
      onConnectionStateChange: (state) => states.push(state),
    })
    expect(fetchStarted).toHaveBeenCalledTimes(1)

    controller.abort()
    settleFetch?.(
      endlessEventStream(
        'id: late_cursor\nevent: notifications.changed\ndata: {}\n\n',
      ),
    )
    await running

    expect(states).toEqual([])
    expect(changed).toBe(0)
  })

  test('removes a completed reconnect timer abort listener', async () => {
    vi.useFakeTimers()
    try {
      const controller = new AbortController()
      const removeListener = vi.spyOn(
        controller.signal,
        'removeEventListener',
      )
      const fetcher = vi.fn(async () => {
        throw new TypeError('network unavailable')
      })
      const client = createNotificationEventStreamClient({
        getAccessToken: () => 'token',
        onUnauthorized: async () => false,
        fetcher,
      })
      const running = client.subscribe({
        signal: controller.signal,
        onChanged: () => {},
        onConnectionStateChange: () => {},
      })
      await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1))

      await vi.advanceTimersByTimeAsync(1_000)
      await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))

      expect(removeListener).toHaveBeenCalledWith(
        'abort',
        expect.any(Function),
      )
      controller.abort()
      await running
    } finally {
      vi.useRealTimers()
    }
  })

  test.each([
    [401, AuthErrorCode.TOKEN_NAMESPACE_MISMATCH],
    [403, AuthErrorCode.ACCOUNT_RESTRICTED],
  ])('stops with unavailable on fatal %i responses', async (status, code) => {
    const states: string[] = []
    const onUnauthorized = vi.fn(async () => false)
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'token',
      onUnauthorized,
      fetcher: async () =>
        new Response(JSON.stringify({ code, message: 'fatal' }), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
    })

    await client.subscribe({
      signal: new AbortController().signal,
      onChanged: () => {},
      onConnectionStateChange: (state) => states.push(state),
    })

    expect(states).toEqual(['unavailable'])
    expect(onUnauthorized).toHaveBeenCalledTimes(status === 401 ? 1 : 0)
  })

  test('stops after a refreshed request also returns 401', async () => {
    const states: string[] = []
    const onUnauthorized = vi.fn(async () => true)
    const fetcher = vi.fn(async () =>
      new Response(
        JSON.stringify({
          code: AuthErrorCode.ACCESS_TOKEN_EXPIRED,
          message: 'expired',
        }),
        { status: 401, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'token',
      onUnauthorized,
      fetcher,
    })

    await client.subscribe({
      signal: new AbortController().signal,
      onChanged: () => {},
      onConnectionStateChange: (state) => states.push(state),
    })

    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
    expect(states).toEqual(['unavailable'])
  })

  test('rejects a content type that only starts with the SSE media type', async () => {
    const states: string[] = []
    const controller = new AbortController()
    const client = createNotificationEventStreamClient({
      getAccessToken: () => 'token',
      onUnauthorized: async () => false,
      fetcher: async () =>
        new Response(
          new ReadableStream<Uint8Array>({
            start(streamController) {
              streamController.enqueue(
                encoder.encode(
                  'id: wrong_type\nevent: notifications.changed\ndata: {}\n\n',
                ),
              )
            },
          }),
          { headers: { 'Content-Type': 'text/event-stream-invalid' } },
        ),
    })

    await client.subscribe({
      signal: controller.signal,
      onChanged: () => controller.abort(),
      onConnectionStateChange: (state) => states.push(state),
    })

    expect(states).toEqual(['unavailable'])
  })
})
