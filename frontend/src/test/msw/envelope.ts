import { HttpResponse } from 'msw'
import type { ApiErrorBody, ValidationErrorDetail } from '../../shared/api/envelope'
import { SUCCESS_CODE } from '../../shared/api/envelope'

/**
 * MSW 핸들러가 backend 계약과 같은 모양으로 응답하게 하는 도우미다.
 * 손으로 쓴 응답 모양이 계약과 어긋나는 것을 여기서 한 번 막는다.
 */

export function successResponse<T>(data: T | null, message = '성공했습니다.') {
  return HttpResponse.json({ code: SUCCESS_CODE, message, data })
}

export function errorResponse(
  status: number,
  code: string,
  message: string,
  details?: ValidationErrorDetail[],
) {
  const body: ApiErrorBody = details ? { code, message, details } : { code, message }
  return HttpResponse.json(body, { status })
}
