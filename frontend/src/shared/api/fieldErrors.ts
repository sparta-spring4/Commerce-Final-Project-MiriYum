import { isApiError } from './apiError'

/** 서버 검증 오류의 필드별 사유를 폼 필드 오류 맵으로 옮긴다. */
export function fieldErrorsFromApiError(error: unknown): Readonly<Record<string, string>> {
  if (!isApiError(error)) return {}
  const errors: Record<string, string> = {}
  for (const detail of error.details) errors[detail.field] = detail.reason
  return errors
}
