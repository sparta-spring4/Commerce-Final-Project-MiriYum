import { isApiError, isNetworkError } from '../../../../shared/api/apiError'

export function pickupOperatorErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버 응답을 확인하지 못했습니다. 새 명령을 보내기 전에 최신 상태를 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case 'PICKUP_001':
      return '이 매장의 픽업 예약을 찾을 수 없습니다.'
    case 'PICKUP_002':
      return '현재 매장에서는 픽업을 운영할 수 없습니다.'
    case 'PICKUP_005':
      return '현재 픽업 상태에서는 처리할 수 없습니다. 최신 상태를 확인해 주세요.'
    case 'PICKUP_006':
      return '현재 시각에는 픽업을 취소할 수 없습니다.'
    case 'STORE_003':
      return '이 매장을 관리할 권한이 없습니다.'
    case 'COMMON_007':
      return '이전 요청과 다른 내용을 같은 키로 보낼 수 없습니다. 상태를 다시 확인해 주세요.'
    default:
      return error.status === 409
        ? '방금 픽업 상태가 바뀌었습니다. 최신 상태를 확인해 주세요.'
        : '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
