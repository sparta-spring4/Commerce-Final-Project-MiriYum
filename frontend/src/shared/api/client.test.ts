import { http, HttpResponse } from 'msw'
import { describe, expect, test, vi } from 'vitest'
import { server } from '../../test/msw/server'
import { errorResponse, successResponse } from '../../test/msw/envelope'
import { ApiError, NetworkError, isApiError, isNetworkError } from './apiError'
import { CommonErrorCode } from './envelope'
import { createApiClient } from './client'
import { IDEMPOTENCY_KEY_HEADER } from './idempotencyKey'

const PATH = '/api/v1/store-categories'

function client(dependencies = {}) {
  return createApiClient(dependencies)
}

describe('성공 응답', () => {
  test('봉투를 벗겨 data만 반환한다', async () => {
    server.use(http.get(PATH, () => successResponse({ items: [{ code: 'KOREAN' }] })))

    const result = await client()<{ items: { code: string }[] }>(PATH)

    expect(result.items[0].code).toBe('KOREAN')
  })

  test('빈 배열을 정상 결과로 반환하고 오류로 바꾸지 않는다', async () => {
    server.use(http.get(PATH, () => successResponse({ items: [] })))

    const result = await client()<{ items: unknown[] }>(PATH)

    expect(result.items).toEqual([])
  })

  test('data가 null이면 null을 반환하고 오류로 바꾸지 않는다', async () => {
    server.use(http.get(PATH, () => successResponse(null)))

    await expect(client()(PATH)).resolves.toBeNull()
  })

  test('204 No Content를 오류로 바꾸지 않는다', async () => {
    server.use(http.delete(PATH, () => new HttpResponse(null, { status: 204 })))

    await expect(client()(PATH, { method: 'DELETE' })).resolves.toBeUndefined()
  })
})

describe('오류 응답', () => {
  const cases = [
    [400, CommonErrorCode.VALIDATION_FAILED],
    [403, 'AUTH_011'],
    [404, CommonErrorCode.ENDPOINT_NOT_FOUND],
    [409, CommonErrorCode.IDEMPOTENCY_KEY_REUSED],
    [429, CommonErrorCode.TOO_MANY_REQUESTS],
    [503, CommonErrorCode.SERVICE_UNAVAILABLE],
  ] as const

  test.each(cases)('%i은 status와 code를 함께 보존한다', async (status, code) => {
    server.use(http.get(PATH, () => errorResponse(status, code, '실패했습니다.')))

    const error = await client()(PATH).catch((thrown: unknown) => thrown)

    expect(isApiError(error)).toBe(true)
    expect((error as ApiError).status).toBe(status)
    expect((error as ApiError).code).toBe(code)
  })

  test('검증 오류의 필드 details를 보존한다', async () => {
    server.use(
      http.get(PATH, () =>
        errorResponse(400, CommonErrorCode.VALIDATION_FAILED, '입력값이 올바르지 않습니다.', [
          { field: 'nickname', message: '길이를 확인해 주세요.' },
        ]),
      ),
    )

    const error = (await client()(PATH).catch((thrown: unknown) => thrown)) as ApiError

    expect(error.details).toHaveLength(1)
    expect(error.details[0].field).toBe('nickname')
  })

  test('오류 모양이 아닌 응답에도 코드를 지어내지 않는다', async () => {
    server.use(http.get(PATH, () => new HttpResponse('<html>500</html>', { status: 500 })))

    const error = (await client()(PATH).catch((thrown: unknown) => thrown)) as ApiError

    expect(isApiError(error)).toBe(true)
    expect(error.code).toBe('HTTP_500')
  })
})

describe('네트워크 실패', () => {
  test('서버 코드가 있는 오류로 위장하지 않는다', async () => {
    server.use(http.get(PATH, () => HttpResponse.error()))

    const error = await client()(PATH).catch((thrown: unknown) => thrown)

    expect(isNetworkError(error)).toBe(true)
    expect(isApiError(error)).toBe(false)
  })

  test('JSON이 아닌 성공 응답도 네트워크 실패로 구분한다', async () => {
    server.use(http.get(PATH, () => new HttpResponse('not json', { status: 200 })))

    const error = await client()(PATH).catch((thrown: unknown) => thrown)

    expect(error).toBeInstanceOf(NetworkError)
  })
})

describe('인증 이음새', () => {
  test('토큰 공급자가 없으면 Authorization을 붙이지 않는다', async () => {
    let authorization: string | null = 'unset'
    server.use(
      http.get(PATH, ({ request }) => {
        authorization = request.headers.get('Authorization')
        return successResponse(null)
      }),
    )

    await client()(PATH)

    expect(authorization).toBeNull()
  })

  test('토큰 공급자가 준 값을 Bearer로 보낸다', async () => {
    let authorization: string | null = null
    server.use(
      http.get(PATH, ({ request }) => {
        authorization = request.headers.get('Authorization')
        return successResponse(null)
      }),
    )

    await client({ getAccessToken: () => 'token-abc' })(PATH)

    expect(authorization).toBe('Bearer token-abc')
  })

  test('401 후처리가 없으면 재시도 없이 던진다', async () => {
    let calls = 0
    server.use(
      http.get(PATH, () => {
        calls += 1
        return errorResponse(401, 'AUTH_003', '인증이 필요합니다.')
      }),
    )

    const error = (await client()(PATH).catch((thrown: unknown) => thrown)) as ApiError

    expect(calls).toBe(1)
    expect(error.code).toBe('AUTH_003')
  })

  test('401 후처리가 true를 반환하면 한 번 재시도한다', async () => {
    let calls = 0
    server.use(
      http.get(PATH, () => {
        calls += 1
        return calls === 1
          ? errorResponse(401, 'AUTH_003', '인증이 필요합니다.')
          : successResponse({ ok: true })
      }),
    )
    const onUnauthorized = vi.fn().mockResolvedValue(true)

    const result = await client({ onUnauthorized })<{ ok: boolean }>(PATH)

    expect(onUnauthorized).toHaveBeenCalledTimes(1)
    expect(calls).toBe(2)
    expect(result.ok).toBe(true)
  })
})

describe('멱등 키', () => {
  test('전달한 키를 헤더로 보낸다', async () => {
    let sent: string | null = null
    server.use(
      http.post(PATH, ({ request }) => {
        sent = request.headers.get(IDEMPOTENCY_KEY_HEADER)
        return successResponse(null)
      }),
    )

    await client()(PATH, { method: 'POST', body: {}, idempotencyKey: 'key-1' })

    expect(sent).toBe('key-1')
  })

  test('키를 주지 않으면 헤더를 붙이지 않는다', async () => {
    let sent: string | null = 'unset'
    server.use(
      http.get(PATH, ({ request }) => {
        sent = request.headers.get(IDEMPOTENCY_KEY_HEADER)
        return successResponse(null)
      }),
    )

    await client()(PATH)

    expect(sent).toBeNull()
  })
})
