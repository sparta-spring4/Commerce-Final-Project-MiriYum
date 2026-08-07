import type { ValidationErrorDetail } from './envelope'

/**
 * 서버가 확정한 오류다. status와 code를 함께 보존해 화면이 둘로 판정할 수 있게 한다.
 * 서버 오류 코드를 클라이언트가 자체 해석해 성공으로 바꾸지 않는다.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: ValidationErrorDetail[]

  constructor(params: {
    status: number
    code: string
    message: string
    details?: ValidationErrorDetail[]
  }) {
    super(params.message)
    this.name = 'ApiError'
    this.status = params.status
    this.code = params.code
    this.details = params.details ?? []
  }
}

/**
 * 서버에 닿지 못했거나 응답을 해석하지 못한 실패다.
 * 서버가 준 코드가 없으므로 ApiError로 위장하지 않고 별도 타입으로 둔다.
 * 화면은 이 오류를 "재시도 가능"으로 다루고 확정 실패로 표시하지 않는다.
 */
export class NetworkError extends Error {
  readonly cause?: unknown

  constructor(message: string, cause?: unknown) {
    super(message)
    this.name = 'NetworkError'
    this.cause = cause
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

export function isNetworkError(error: unknown): error is NetworkError {
  return error instanceof NetworkError
}

/** 서버가 특정 오류 코드를 반환했는지 확인한다. */
export function hasErrorCode(error: unknown, code: string): boolean {
  return isApiError(error) && error.code === code
}
