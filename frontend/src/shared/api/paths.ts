import type { paths as AuthAccountPaths } from './generated/auth-account'
import type { paths as MenuHoldPickupPaths } from './generated/menu-hold-pickup'
import type { paths as ReservationPaths } from './generated/reservation'
import type { paths as StoreSearchPaths } from './generated/store-search'

/**
 * 기능별 OpenAPI 문서에서 생성한 paths를 하나의 API 표면으로 합친다.
 * 생성 파일은 직접 수정하지 않고 여기서 타입으로만 조합한다.
 */

type Overlap<A, B> = Extract<keyof A, keyof B>

/**
 * 두 문서가 같은 경로를 선언하면 컴파일이 실패한다.
 *
 * 교집합 타입(&)은 겹치는 키를 조용히 합쳐 버려서, 서로 다른 operation이
 * 하나로 뭉개져도 알 수 없다. 아래 제약이 그 상황을 typecheck에서 드러낸다.
 */
type AssertNoOverlap<T extends never> = T

export type NoPathOverlap =
  | AssertNoOverlap<Overlap<AuthAccountPaths, StoreSearchPaths>>
  | AssertNoOverlap<Overlap<AuthAccountPaths, ReservationPaths>>
  | AssertNoOverlap<Overlap<AuthAccountPaths, MenuHoldPickupPaths>>
  | AssertNoOverlap<Overlap<StoreSearchPaths, ReservationPaths>>
  | AssertNoOverlap<Overlap<StoreSearchPaths, MenuHoldPickupPaths>>
  | AssertNoOverlap<Overlap<ReservationPaths, MenuHoldPickupPaths>>

export type ApiPaths = AuthAccountPaths &
  StoreSearchPaths &
  ReservationPaths &
  MenuHoldPickupPaths

/** OpenAPI에 존재하는 경로만 허용한다. */
export type ApiPath = keyof ApiPaths & string

export type HttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete'

/** 해당 경로가 실제로 선언한 method만 허용한다. */
export type MethodOf<P extends ApiPath> = Extract<keyof ApiPaths[P], HttpMethod>

export type OperationOf<
  P extends ApiPath,
  M extends MethodOf<P>,
> = ApiPaths[P][M]

/** 경로 템플릿의 {name} 자리를 이름으로 뽑는다. */
type PathParamName<P extends string> =
  P extends `${string}{${infer Name}}${infer Rest}`
    ? Name | PathParamName<Rest>
    : never

/** 템플릿에 자리가 없으면 pathParams를 받지 않는다. */
export type PathParamsOf<P extends ApiPath> = [PathParamName<P>] extends [never]
  ? { pathParams?: never }
  : { pathParams: Record<PathParamName<P>, string | number> }

type JsonRequestBody<Op> = Op extends {
  requestBody: { content: { 'application/json': infer Body } }
}
  ? Body
  : never

/** 요청 본문은 해당 operation의 생성 타입으로만 받는다. */
export type RequestBodyOf<Op> = [JsonRequestBody<Op>] extends [never]
  ? { body?: never }
  : { body: JsonRequestBody<Op> }

/**
 * 계약이 선언한 성공 status다. 네 문서를 훑어 200과 201만 존재하며
 * 전부 application/json 본문을 가진다. 204를 선언한 operation은 없다.
 */
type SuccessStatus = 200 | 201

/**
 * 성공 응답 타입. 생성 타입이 이미 {code, message, data} 봉투를 담고 있으므로
 * 응답 필드를 손으로 다시 정의하지 않는다.
 */
export type SuccessBodyOf<Op> = Op extends { responses: infer Responses }
  ? {
      [S in Extract<keyof Responses, SuccessStatus>]: Responses[S] extends {
        content: { 'application/json': infer Body }
      }
        ? Body
        : never
    }[Extract<keyof Responses, SuccessStatus>]
  : never

/** operation이 Idempotency-Key 헤더를 요구하면 호출에서도 필수로 만든다. */
export type IdempotencyOf<Op> = Op extends {
  parameters: { header: { 'Idempotency-Key': unknown } }
}
  ? { idempotencyKey: string }
  : { idempotencyKey?: never }

/**
 * operation이 X-CSRF-TOKEN 헤더를 요구하면 호출에서도 필수로 만든다.
 *
 * CSRF double-submit은 로그아웃처럼 Refresh 쿠키로 동작하는 요청에만 걸린다.
 * 어떤 경로가 요구하는지 손으로 기억하지 않고 계약이 정하게 한다.
 */
export type CsrfOf<Op> = Op extends {
  parameters: { header: { 'X-CSRF-TOKEN': unknown } }
}
  ? { csrfToken: string }
  : { csrfToken?: never }
