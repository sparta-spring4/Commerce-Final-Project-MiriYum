import { ApiError, NetworkError } from './apiError'
import { IDEMPOTENCY_KEY_HEADER } from './idempotencyKey'
import { isApiErrorBody, type ApiSuccessBody } from './envelope'

/**
 * 인증 이음새. 이 모듈은 토큰을 보관하거나 재발급을 판단하지 않는다.
 *
 * 계정 shell이 둘이고 서로 토큰을 공유하지 않으므로, 클라이언트가 전역 단일 토큰을
 * 들고 있으면 shell 분리 규칙이 깨진다. 따라서 공급자를 주입으로 받는다.
 */
export interface ApiClientDependencies {
  /** 요청 직전에 호출한다. null이면 Authorization 헤더를 붙이지 않는다. */
  getAccessToken?: () => string | null
  /**
   * 401 응답에 대해 호출한다. 재발급 후 재시도하려면 true를 반환한다.
   * 기본 동작은 재시도 없이 ApiError를 던지는 것이다.
   */
  onUnauthorized?: (error: ApiError) => Promise<boolean>
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** 생성 타입으로 제한한다. OpenAPI에 없는 필드를 보내면 backend가 400으로 거절한다. */
  body?: unknown
  /** 한 작업 시도의 멱등 키. 재시도 동안 같은 값을 넘긴다. */
  idempotencyKey?: string
  headers?: Record<string, string>
  signal?: AbortSignal
}

const JSON_CONTENT_TYPE = 'application/json'

export type ApiClient = <T>(path: string, options?: RequestOptions) => Promise<T>

export function createApiClient(
  dependencies: ApiClientDependencies = {},
): ApiClient {
  const { getAccessToken, onUnauthorized } = dependencies

  async function send(path: string, options: RequestOptions): Promise<Response> {
    const headers: Record<string, string> = { ...options.headers }

    if (options.body !== undefined) {
      headers['Content-Type'] = JSON_CONTENT_TYPE
    }
    if (options.idempotencyKey) {
      headers[IDEMPOTENCY_KEY_HEADER] = options.idempotencyKey
    }
    const token = getAccessToken?.()
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    try {
      return await fetch(path, {
        method: options.method ?? 'GET',
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: options.signal,
        // Refresh·CSRF 쿠키는 same-origin 프록시를 통해서만 오간다.
        credentials: 'same-origin',
      })
    } catch (cause) {
      throw new NetworkError('서버에 연결하지 못했습니다.', cause)
    }
  }

  /**
   * 본문을 JSON으로 읽지 못했을 때 쓰는 표식이다.
   * 해석 실패의 의미가 status에 따라 다르므로 여기서 바로 던지지 않고 호출자가 판정한다.
   * 성공 status면 계약을 못 읽은 것이라 네트워크 실패, 오류 status면 서버가 확정한 실패다.
   */
  const UNPARSEABLE = Symbol('unparseable')

  async function readBody(response: Response): Promise<unknown | typeof UNPARSEABLE> {
    if (response.status === 204) {
      return null
    }
    const text = await response.text()
    if (text.length === 0) {
      return null
    }
    try {
      return JSON.parse(text)
    } catch {
      return UNPARSEABLE
    }
  }

  function toApiError(status: number, body: unknown): ApiError {
    if (isApiErrorBody(body)) {
      return new ApiError({
        status,
        code: body.code,
        message: body.message,
        details: body.details,
      })
    }
    // 오류 모양이 아닌 응답은 계약 위반이다. 코드를 지어내지 않고 status만 보존한다.
    return new ApiError({
      status,
      code: `HTTP_${status}`,
      message: '알 수 없는 오류가 발생했습니다.',
    })
  }

  return async function requestApi<T>(
    path: string,
    options: RequestOptions = {},
  ): Promise<T> {
    let response = await send(path, options)

    if (response.status === 401 && onUnauthorized) {
      const error = toApiError(401, await readBody(response))
      if (await onUnauthorized(error)) {
        response = await send(path, options)
      } else {
        throw error
      }
    }

    const body = await readBody(response)

    if (!response.ok) {
      throw toApiError(response.status, body)
    }

    if (body === UNPARSEABLE) {
      throw new NetworkError('서버 응답을 해석하지 못했습니다.')
    }

    // 성공 응답의 data는 null일 수 있다. 빈 배열과 마찬가지로 오류가 아니다.
    return (body as ApiSuccessBody<T>)?.data as T
  }
}
