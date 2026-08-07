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

/** 필드 단위 검증 오류. */
export interface ValidationErrorDetail {
  field: string
  message: string
}

/** 모든 API 오류가 따르는 응답 본문. */
export interface ApiErrorBody {
  code: string
  message: string
  details?: ValidationErrorDetail[]
}

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

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 응답 본문이 오류 모양인지 판정한다. */
export function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (!isPlainObject(value)) {
    return false
  }
  return typeof value.code === 'string' && typeof value.message === 'string'
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
