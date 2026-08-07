/**
 * backend는 성공과 실패의 본문 모양이 다르다. 실패는 ApiResponse로 감싸지 않는다.
 * 따라서 HTTP status로 두 모양을 먼저 가른 뒤 code를 읽는다.
 */

/** 성공 응답의 고정 code. */
export const SUCCESS_CODE = 'SUCCESS'

/** 일반 JSON 성공 응답의 공통 봉투. */
export interface ApiSuccessBody<T> {
  code: typeof SUCCESS_CODE
  message: string
  /** null은 정상적인 응답 데이터 없음이며 오류가 아니다. */
  data: T | null
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

/** 응답 본문이 오류 모양인지 판정한다. */
export function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const body = value as Record<string, unknown>
  return typeof body.code === 'string' && typeof body.message === 'string'
}
