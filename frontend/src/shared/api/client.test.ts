import { http, HttpResponse } from 'msw'
import { describe, expect, test, vi } from 'vitest'
import { server } from '../../test/msw/server'
import { errorResponse, successResponse } from '../../test/msw/envelope'
import {
  ApiError,
  isApiContractError,
  isApiError,
  isNetworkError,
} from './apiError'
import { CommonErrorCode } from './envelope'
import { createApiClient, type ApiClientDependencies } from './client'
import { IDEMPOTENCY_KEY_HEADER } from './idempotencyKey'

const CATEGORIES = '/api/v1/store-categories'
const ACCOUNT = '/api/v1/consumers/me'
const RESERVATION = '/api/v1/consumers/me/reservations/{reservationId}'

function client(dependencies: ApiClientDependencies = {}) {
  return createApiClient(dependencies)
}

function getCategories(dependencies: ApiClientDependencies = {}) {
  return client(dependencies)(CATEGORIES, { method: 'get' })
}

describe('성공 봉투', () => {
  test('code·message·data를 구분해 노출한다', async () => {
    server.use(
      http.get(CATEGORIES, () =>
        successResponse({ items: [{ code: 'KOREAN', displayName: '한식' }] }, '조회했습니다.'),
      ),
    )

    const result = await getCategories()

    expect(result.code).toBe('SUCCESS')
    expect(result.message).toBe('조회했습니다.')
    expect(result.data.items[0].code).toBe('KOREAN')
  })

  test('빈 배열을 정상 성공으로 유지한다', async () => {
    server.use(http.get(CATEGORIES, () => successResponse({ items: [] })))

    const result = await getCategories()

    expect(result.data.items).toEqual([])
  })

  test('data가 null이어도 정상 성공으로 유지한다', async () => {
    server.use(http.get(CATEGORIES, () => successResponse(null)))

    const result = await getCategories()

    expect(result.code).toBe('SUCCESS')
    expect(result.data).toBeNull()
  })
})

describe('성공 봉투 검증 — 2xx라도 통과시키지 않는다', () => {
  test('임의 JSON은 성공이 아니다', async () => {
    server.use(http.get(CATEGORIES, () => HttpResponse.json({ items: [] })))

    const error = await getCategories().catch((thrown: unknown) => thrown)

    expect(isApiContractError(error)).toBe(true)
  })

  test('code가 SUCCESS가 아니면 성공이 아니다', async () => {
    server.use(
      http.get(CATEGORIES, () =>
        HttpResponse.json({ code: 'PARTIAL', message: '부분 성공', data: null }),
      ),
    )

    const error = await getCategories().catch((thrown: unknown) => thrown)

    expect(isApiContractError(error)).toBe(true)
    expect((error as { violation: string }).violation).toBe('codeNotSuccess')
  })

  test('message가 문자열이 아니면 성공이 아니다', async () => {
    server.use(
      http.get(CATEGORIES, () =>
        HttpResponse.json({ code: 'SUCCESS', message: 42, data: null }),
      ),
    )

    const error = await getCategories().catch((thrown: unknown) => thrown)

    expect((error as { violation: string }).violation).toBe('messageNotString')
  })

  test('data 필드가 없으면 성공이 아니다', async () => {
    server.use(
      http.get(CATEGORIES, () =>
        HttpResponse.json({ code: 'SUCCESS', message: '조회했습니다.' }),
      ),
    )

    const error = await getCategories().catch((thrown: unknown) => thrown)

    expect((error as { violation: string }).violation).toBe('dataMissing')
  })

  test('배열 본문은 봉투가 아니다', async () => {
    server.use(http.get(CATEGORIES, () => HttpResponse.json([{ code: 'KOREAN' }])))

    const error = await getCategories().catch((thrown: unknown) => thrown)

    expect((error as { violation: string }).violation).toBe('notAnObject')
  })

  test('본문 없는 204를 성공으로 처리한다', async () => {
    server.use(http.get(CATEGORIES, () => new HttpResponse(null, { status: 204 })))

    const result = await getCategories()

    expect(result).toBeUndefined()
  })

  test('계약 위반을 서버 오류 코드로 위장하지 않는다', async () => {
    server.use(http.get(CATEGORIES, () => HttpResponse.json({ anything: true })))

    const error = await getCategories().catch((thrown: unknown) => thrown)

    expect(isApiError(error)).toBe(false)
    expect(isNetworkError(error)).toBe(false)
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
    server.use(http.get(CATEGORIES, () => errorResponse(status, code, '실패했습니다.')))

    const error = await getCategories().catch((thrown: unknown) => thrown)

    expect(isApiError(error)).toBe(true)
    expect((error as ApiError).status).toBe(status)
    expect((error as ApiError).code).toBe(code)
    expect((error as ApiError).message).toBe('실패했습니다.')
  })

  // 계약(mvp1-common ValidationErrorDetail)의 필수 필드는 field와 reason이다.
  test('검증 오류의 field와 reason을 보존한다', async () => {
    server.use(
      http.get(CATEGORIES, () =>
        errorResponse(400, CommonErrorCode.VALIDATION_FAILED, '입력값이 올바르지 않습니다.', [
          { field: 'nickname', reason: '길이를 확인해 주세요.' },
          { field: 'menuSelections[0].quantity', reason: '1 이상이어야 합니다.' },
        ]),
      ),
    )

    const error = (await getCategories().catch((thrown: unknown) => thrown)) as ApiError

    expect(error.details).toHaveLength(2)
    expect(error.details[0]).toEqual({
      field: 'nickname',
      reason: '길이를 확인해 주세요.',
    })
    expect(error.details[1].field).toBe('menuSelections[0].quantity')
    expect(error.details[1].reason).toBe('1 이상이어야 합니다.')
  })

  test('details가 없는 오류는 빈 배열로 노출한다', async () => {
    server.use(
      http.get(CATEGORIES, () =>
        errorResponse(400, CommonErrorCode.VALIDATION_FAILED, '입력값이 올바르지 않습니다.'),
      ),
    )

    const error = (await getCategories().catch((thrown: unknown) => thrown)) as ApiError

    expect(error.details).toEqual([])
  })

  // 계약을 어긋난 오류 본문은 일반 ApiErrorBody로 인정하지 않는다.
  // 화면이 분기에 쓸 수 없는 code를 그대로 넘기지 않고 status만 보존한다.
  test('code 형식이 계약과 다르면 서버 code를 그대로 쓰지 않는다', async () => {
    server.use(
      http.get(CATEGORIES, () =>
        HttpResponse.json({ code: 'INVALID', message: '실패했습니다.' }, { status: 400 }),
      ),
    )

    const error = (await getCategories().catch((thrown: unknown) => thrown)) as ApiError

    expect(error.status).toBe(400)
    expect(error.code).toBe('HTTP_400')
  })

  test('message가 비어 있으면 서버 code를 그대로 쓰지 않는다', async () => {
    server.use(
      http.get(CATEGORIES, () =>
        HttpResponse.json({ code: 'COMMON_001', message: '' }, { status: 400 }),
      ),
    )

    const error = (await getCategories().catch((thrown: unknown) => thrown)) as ApiError

    expect(error.code).toBe('HTTP_400')
  })

  test('오류 모양이 아닌 응답에도 코드를 지어내지 않는다', async () => {
    server.use(http.get(CATEGORIES, () => new HttpResponse('<html>500</html>', { status: 500 })))

    const error = (await getCategories().catch((thrown: unknown) => thrown)) as ApiError

    expect(isApiError(error)).toBe(true)
    expect(error.code).toBe('HTTP_500')
  })
})

describe('네트워크 실패', () => {
  test('서버 코드가 있는 오류로 위장하지 않는다', async () => {
    server.use(http.get(CATEGORIES, () => HttpResponse.error()))

    const error = await getCategories().catch((thrown: unknown) => thrown)

    expect(isNetworkError(error)).toBe(true)
    expect(isApiError(error)).toBe(false)
  })
})

describe('인증 이음새', () => {
  test('토큰 공급자가 없으면 Authorization을 붙이지 않는다', async () => {
    let authorization: string | null = 'unset'
    server.use(
      http.get(CATEGORIES, ({ request }) => {
        authorization = request.headers.get('Authorization')
        return successResponse(null)
      }),
    )

    await getCategories()

    expect(authorization).toBeNull()
  })

  test('토큰 공급자가 준 값을 Bearer로 보낸다', async () => {
    let authorization: string | null = null
    server.use(
      http.get(CATEGORIES, ({ request }) => {
        authorization = request.headers.get('Authorization')
        return successResponse(null)
      }),
    )

    await getCategories({ getAccessToken: () => 'token-abc' })

    expect(authorization).toBe('Bearer token-abc')
  })

  test('401 후처리가 없으면 재시도 없이 던진다', async () => {
    let calls = 0
    server.use(
      http.get(CATEGORIES, () => {
        calls += 1
        return errorResponse(401, 'AUTH_003', '인증이 필요합니다.')
      }),
    )

    const error = (await getCategories().catch((thrown: unknown) => thrown)) as ApiError

    expect(calls).toBe(1)
    expect(error.code).toBe('AUTH_003')
  })

  test('401 후처리가 true를 반환하면 한 번 재시도한다', async () => {
    let calls = 0
    server.use(
      http.get(CATEGORIES, () => {
        calls += 1
        return calls === 1
          ? errorResponse(401, 'AUTH_003', '인증이 필요합니다.')
          : successResponse({ items: [] })
      }),
    )
    const onUnauthorized = vi.fn().mockResolvedValue(true)

    const result = await getCategories({ onUnauthorized })

    expect(onUnauthorized).toHaveBeenCalledTimes(1)
    expect(calls).toBe(2)
    expect(result.code).toBe('SUCCESS')
  })
})

describe('멱등 키', () => {
  test('계약이 요구하는 operation에서 헤더로 보낸다', async () => {
    let sent: string | null = null
    server.use(
      http.patch(ACCOUNT, ({ request }) => {
        sent = request.headers.get(IDEMPOTENCY_KEY_HEADER)
        return successResponse(null)
      }),
    )

    await client()(ACCOUNT, {
      method: 'patch',
      body: { nickname: '미리' },
      idempotencyKey: 'key-1',
    })

    expect(sent).toBe('key-1')
  })

  test('요구하지 않는 operation에는 헤더를 붙이지 않는다', async () => {
    let sent: string | null = 'unset'
    server.use(
      http.get(CATEGORIES, ({ request }) => {
        sent = request.headers.get(IDEMPOTENCY_KEY_HEADER)
        return successResponse(null)
      }),
    )

    await getCategories()

    expect(sent).toBeNull()
  })
})

describe('경로 변수와 query', () => {
  test('경로 변수를 치환해 호출한다', async () => {
    let seen = ''
    server.use(
      http.get('/api/v1/consumers/me/reservations/:reservationId', ({ params }) => {
        seen = String(params.reservationId)
        return successResponse(null)
      }),
    )

    await client()(RESERVATION, { method: 'get', pathParams: { reservationId: 42 } })

    expect(seen).toBe('42')
  })

  test('query를 직렬화하고 undefined는 보내지 않는다', async () => {
    let search = ''
    server.use(
      http.get(CATEGORIES, ({ request }) => {
        search = new URL(request.url).search
        return successResponse(null)
      }),
    )

    await client()(CATEGORIES, {
      method: 'get',
      query: { keyword: '한식', page: 0, omitted: undefined },
    })

    expect(search).toBe('?keyword=%ED%95%9C%EC%8B%9D&page=0')
  })
})
