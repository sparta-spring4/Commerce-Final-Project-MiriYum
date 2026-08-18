import { ApiContractError, ApiError, NetworkError } from './apiError'
import { IDEMPOTENCY_KEY_HEADER } from './idempotencyKey'
import { checkSuccessEnvelope, isApiErrorBody, type ApiSuccess } from './envelope'
import type {
  AdminAuditContextOf,
  AdminCaseRefOf,
  AdminReauthenticationOf,
  ApiPath,
  CsrfOf,
  IdempotencyOf,
  IfMatchOf,
  MethodOf,
  OperationOf,
  PathParamsOf,
  RequestBodyOf,
  SuccessBodyOf,
} from './paths'

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
  /**
   * 403 응답을 shell에 알린다. 재시도하지 않으며 오류는 그대로 던진다.
   *
   * 403 중에는 요청 하나의 실패가 아니라 세션 상태를 뜻하는 것이 있다.
   * 플랫폼 운영자의 `AUTH_012`가 그렇다. 임시 비밀번호 세션으로 업무 API를
   * 부르면 매번 403이 오는데, shell이 이를 모르면 화면은 일반 오류만 반복해서
   * 보여 주고 사용자는 비밀번호 변경 화면으로 갈 방법을 찾지 못한다.
   */
  onForbidden?: (error: ApiError) => void
}

/** OpenAPI가 타이핑하지 않는 부수 입력. */
interface CommonRequestOptions {
  query?: Record<string, string | number | boolean | undefined>
  signal?: AbortSignal
}

/**
 * 호출 옵션은 경로와 method가 정해지면 나머지가 따라온다.
 * 본문·경로 변수·멱등 키는 해당 operation의 생성 타입에서 나온다.
 */
export type RequestOptions<P extends ApiPath, M extends MethodOf<P>> = {
  method: M
} & PathParamsOf<P> &
  RequestBodyOf<OperationOf<P, M>> &
  IdempotencyOf<OperationOf<P, M>> &
  CsrfOf<OperationOf<P, M>> &
  IfMatchOf<OperationOf<P, M>> &
  AdminReauthenticationOf<P, OperationOf<P, M>> &
  AdminAuditContextOf<OperationOf<P, M>> &
  AdminCaseRefOf<OperationOf<P, M>> &
  CommonRequestOptions

/** 성공 응답은 봉투 그대로 노출한다. 화면이 code·message·data를 구분해 쓴다. */
export type ApiResult<P extends ApiPath, M extends MethodOf<P>> = ApiSuccess<
  SuccessBodyOf<OperationOf<P, M>> extends { data: infer D } ? D : never
>

export type ApiClient = <P extends ApiPath, M extends MethodOf<P>>(
  path: P,
  options: RequestOptions<P, M>,
) => Promise<ApiResult<P, M>>

const JSON_CONTENT_TYPE = 'application/json'

/**
 * CSRF double-submit 헤더 이름.
 *
 * 계약이 `X-CSRF-TOKEN` 하나로 고정하고 서버가 쿠키 값과 대조한다.
 * 값은 shell이 자기 namespace 쿠키에서 읽어 넘긴다. 이 모듈은 쿠키를 읽지 않는다.
 */
const CSRF_TOKEN_HEADER = 'X-CSRF-TOKEN'

/**
 * 플랫폼 운영자 명령·조회가 요구하는 헤더 이름.
 *
 * `If-Match`는 대상 version, 재인증은 고위험 명령 승인, 사건·사유는 감사 조회의
 * 인가 조건이다. 값은 화면이 넘긴다. 이 모듈은 값을 만들거나 기본값을 채우지 않는다.
 */
const IF_MATCH_HEADER = 'If-Match'
const ADMIN_REAUTHENTICATION_HEADER = 'X-Admin-Reauthentication'
const ADMIN_CASE_ID_HEADER = 'X-Admin-Case-Id'
const ADMIN_CASE_VERSION_HEADER = 'X-Admin-Case-Version'
const ADMIN_REASON_CODE_HEADER = 'X-Admin-Reason-Code'

/**
 * 2xx 본문을 JSON으로 읽지 못했을 때 쓰는 표식이다.
 * 해석 실패의 의미가 status에 따라 다르므로 호출자가 판정한다.
 */
const UNPARSEABLE = Symbol('unparseable')

function buildUrl(
  path: string,
  pathParams: Record<string, string | number> | undefined,
  query: CommonRequestOptions['query'],
): string {
  let url = path
  if (pathParams) {
    for (const [name, value] of Object.entries(pathParams)) {
      url = url.replace(`{${name}}`, encodeURIComponent(String(value)))
    }
  }
  if (!query) {
    return url
  }
  const search = new URLSearchParams()
  for (const [name, value] of Object.entries(query)) {
    if (value !== undefined) {
      search.append(name, String(value))
    }
  }
  const serialized = search.toString()
  return serialized.length > 0 ? `${url}?${serialized}` : url
}

export function createApiClient(
  dependencies: ApiClientDependencies = {},
): ApiClient {
  const { getAccessToken, onUnauthorized, onForbidden } = dependencies

  async function send(
    url: string,
    options: {
      method: string
      body?: unknown
      idempotencyKey?: string
      csrfToken?: string
      ifMatch?: number
      adminReauthentication?: string
      adminAuditContext?: {
        caseId: string
        caseVersion: number
        reasonCode: string
      }
      adminCaseRef?: { caseId: string; caseVersion: number }
      signal?: AbortSignal
    },
  ): Promise<Response> {
    const headers: Record<string, string> = {}

    if (options.body !== undefined) {
      headers['Content-Type'] = JSON_CONTENT_TYPE
    }
    if (options.idempotencyKey) {
      headers[IDEMPOTENCY_KEY_HEADER] = options.idempotencyKey
    }
    if (options.csrfToken) {
      headers[CSRF_TOKEN_HEADER] = options.csrfToken
    }
    // version 0이 유효한 값이므로 존재 여부로 판정한다.
    if (options.ifMatch !== undefined) {
      headers[IF_MATCH_HEADER] = String(options.ifMatch)
    }
    if (options.adminReauthentication) {
      headers[ADMIN_REAUTHENTICATION_HEADER] = options.adminReauthentication
    }
    if (options.adminAuditContext) {
      headers[ADMIN_CASE_ID_HEADER] = options.adminAuditContext.caseId
      headers[ADMIN_CASE_VERSION_HEADER] = String(
        options.adminAuditContext.caseVersion,
      )
      headers[ADMIN_REASON_CODE_HEADER] = options.adminAuditContext.reasonCode
    }
    // 사유 코드 없이 사건만 참조하는 명령이다. 조회와 헤더 구성이 다르다.
    if (options.adminCaseRef) {
      headers[ADMIN_CASE_ID_HEADER] = options.adminCaseRef.caseId
      headers[ADMIN_CASE_VERSION_HEADER] = String(
        options.adminCaseRef.caseVersion,
      )
    }
    const token = getAccessToken?.()
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    try {
      return await fetch(url, {
        method: options.method.toUpperCase(),
        headers,
        body:
          options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: options.signal,
        // Refresh·CSRF 쿠키는 same-origin 프록시를 통해서만 오간다.
        credentials: 'same-origin',
      })
    } catch (cause) {
      throw new NetworkError('서버에 연결하지 못했습니다.', cause)
    }
  }

  async function readBody(response: Response): Promise<unknown> {
    const text = await response.text()
    if (text.length === 0) {
      return UNPARSEABLE
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

  return async function requestApi<P extends ApiPath, M extends MethodOf<P>>(
    path: P,
    options: RequestOptions<P, M>,
  ): Promise<ApiResult<P, M>> {
    const {
      method,
      pathParams,
      body,
      idempotencyKey,
      csrfToken,
      ifMatch,
      adminReauthentication,
      adminAuditContext,
      adminCaseRef,
      query,
      signal,
    } = options as RequestOptions<P, M> & {
      pathParams?: Record<string, string | number>
      body?: unknown
      idempotencyKey?: string
      csrfToken?: string
      ifMatch?: number
      adminReauthentication?: string
      adminAuditContext?: {
        caseId: string
        caseVersion: number
        reasonCode: string
      }
      adminCaseRef?: { caseId: string; caseVersion: number }
    }

    const url = buildUrl(path, pathParams, query)
    const sendOptions = {
      method,
      body,
      idempotencyKey,
      csrfToken,
      ifMatch,
      adminReauthentication,
      adminAuditContext,
      adminCaseRef,
      signal,
    }

    let response = await send(url, sendOptions)

    if (response.status === 401 && onUnauthorized) {
      const error = toApiError(401, await readBody(response))
      if (await onUnauthorized(error)) {
        response = await send(url, sendOptions)
      } else {
        throw error
      }
    }

    const payload = await readBody(response)

    if (!response.ok) {
      const error = toApiError(
        response.status,
        payload === UNPARSEABLE ? undefined : payload,
      )
      if (response.status === 403) {
        onForbidden?.(error)
      }
      throw error
    }

    // 계약에 204를 선언한 operation이 없다. 본문 없는 2xx는 계약 위반이다.
    if (payload === UNPARSEABLE) {
      throw new ApiContractError(response.status, 'notAnObject')
    }

    const checked = checkSuccessEnvelope<
      SuccessBodyOf<OperationOf<P, M>> extends { data: infer D } ? D : never
    >(payload)

    if (!checked.ok) {
      throw new ApiContractError(response.status, checked.violation)
    }

    return checked.value
  }
}
