import type { components as CommonComponents } from './generated/common'

/**
 * backend는 성공과 실패의 본문 모양이 다르다. 실패는 성공 봉투로 감싸지 않는다.
 * 따라서 HTTP status로 두 모양을 먼저 가른 뒤 code를 읽는다.
 */

/** 성공 응답의 고정 code. */
export const SUCCESS_CODE = 'SUCCESS'

/**
 * 성공 봉투를 세 필드로 구분해 노출한다.
 *
 * 데이터 타입 T는 OpenAPI 생성 타입에서 온다. 여기서 응답 필드를 다시 정의하지 않는다.
 */
export interface ApiSuccess<T> {
  code: typeof SUCCESS_CODE
  message: string
  /** null은 정상적인 응답 데이터 없음이며 오류가 아니다. */
  data: T
}

/**
 * 오류 본문 타입은 공통 OpenAPI 문서에서 생성한 것을 단일 소스로 쓴다.
 *
 * 손으로 선언하면 계약과 어긋나도 typecheck가 통과한다. 실제로 필드 이름을
 * `reason`이 아니라 `message`로 잘못 적어 검증 오류 사유가 사라질 수 있었다.
 * 기능별 생성 파일의 `external[...]` 참조에 결합하지 않고 공통 생성 파일만 본다.
 */

/** 필드 단위 검증 오류. `field`와 `reason`이 필수다. */
export type ValidationErrorDetail =
  CommonComponents['schemas']['ValidationErrorDetail']

/** 모든 API 오류가 따르는 응답 본문. */
export type ApiErrorBody = CommonComponents['schemas']['ErrorResponse']

/**
 * 도메인에 속하지 않는 공통 오류 코드다. backend CommonErrorCode와 1:1로 맞춘다.
 * 화면이 문자열 리터럴을 흩뿌리지 않도록 여기서만 정의한다.
 */
export const CommonErrorCode = {
  VALIDATION_FAILED: 'COMMON_001',
  MALFORMED_REQUEST: 'COMMON_002',
  IDEMPOTENCY_KEY_REQUIRED: 'COMMON_003',
  INVALID_IDEMPOTENCY_KEY: 'COMMON_004',
  ENDPOINT_NOT_FOUND: 'COMMON_005',
  METHOD_NOT_ALLOWED: 'COMMON_006',
  IDEMPOTENCY_KEY_REUSED: 'COMMON_007',
  CONCURRENT_MODIFICATION: 'COMMON_008',
  UNSUPPORTED_MEDIA_TYPE: 'COMMON_009',
  TOO_MANY_REQUESTS: 'COMMON_010',
  INTERNAL_SERVER_ERROR: 'COMMON_011',
  SERVICE_UNAVAILABLE: 'COMMON_012',
} as const

export type CommonErrorCodeValue =
  (typeof CommonErrorCode)[keyof typeof CommonErrorCode]

/**
 * 오류 코드 형식이다. 공통 OpenAPI `ErrorResponse.code`의 pattern과 같다.
 * 생성 타입은 `string`으로만 나와 이 제약을 표현하지 못한다.
 */
export const ERROR_CODE_PATTERN = /^[A-Z]+(?:_[A-Z]+)*_[0-9]{3}$/

/** 공통 OpenAPI `ErrorResponse.message`의 minLength. */
const MESSAGE_MIN_LENGTH = 1

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * 검증 오류 항목인지 런타임에서 판정한다.
 *
 * 생성 타입은 컴파일 시점 보호만 제공한다. 서버에서 온 값은 unknown이므로
 * `field`와 `reason`이 실제로 문자열인지 여기서 확인해야 한다.
 */
export function isValidationErrorDetail(
  value: unknown,
): value is ValidationErrorDetail {
  if (!isPlainObject(value)) {
    return false
  }
  return typeof value.field === 'string' && typeof value.reason === 'string'
}

/**
 * 응답 본문이 오류 모양인지 판정한다.
 *
 * `code`는 화면이 분기에 쓰는 값이므로 형식까지 확인한다. `""`나 `"INVALID"`를
 * 통과시키면 분기가 조용히 빗나간다. `message`는 사용자에게 표시하는 값이라
 * 비어 있으면 안 된다.
 *
 * `message`에 `trim()`을 쓰지 않는다. 계약은 `minLength: 1`만 요구하고 공백 문자열을
 * 따로 금지하지 않으므로, 클라이언트가 계약보다 엄격하게 굴지 않는다.
 *
 * `details`는 선택이지만, 있으면 계약을 지켜야 한다. 계약은 `minItems: 1`이므로
 * 빈 배열은 거절한다. 항목 하나라도 모양이 다르면 전체를 오류 본문으로 보지 않는다.
 */
export function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (!isPlainObject(value)) {
    return false
  }
  if (typeof value.code !== 'string' || !ERROR_CODE_PATTERN.test(value.code)) {
    return false
  }
  if (
    typeof value.message !== 'string' ||
    value.message.length < MESSAGE_MIN_LENGTH
  ) {
    return false
  }
  if (!('details' in value) || value.details === undefined) {
    return true
  }
  if (!Array.isArray(value.details) || value.details.length === 0) {
    return false
  }
  return value.details.every(isValidationErrorDetail)
}

/** 봉투 검증 실패 사유. 어떤 조건이 깨졌는지 호출자에게 그대로 전달한다. */
export type EnvelopeViolation =
  | 'notAnObject'
  | 'codeNotSuccess'
  | 'messageNotString'
  | 'dataMissing'

export type EnvelopeCheck<T> =
  | { ok: true; value: ApiSuccess<T> }
  | { ok: false; violation: EnvelopeViolation }

/**
 * 2xx 본문이 실제로 성공 봉투인지 런타임에서 확인한다.
 *
 * 캐스팅으로 넘기면 code가 SUCCESS가 아닌 200이나 임의 JSON도 성공으로 통과한다.
 * data는 존재 여부만 본다. null과 빈 배열은 정상 성공이므로 값으로 거르지 않는다.
 */
export function checkSuccessEnvelope<T>(body: unknown): EnvelopeCheck<T> {
  if (!isPlainObject(body)) {
    return { ok: false, violation: 'notAnObject' }
  }
  if (body.code !== SUCCESS_CODE) {
    return { ok: false, violation: 'codeNotSuccess' }
  }
  if (typeof body.message !== 'string') {
    return { ok: false, violation: 'messageNotString' }
  }
  if (!('data' in body)) {
    return { ok: false, violation: 'dataMissing' }
  }
  return {
    ok: true,
    value: {
      code: SUCCESS_CODE,
      message: body.message,
      data: body.data as T,
    },
  }
}
