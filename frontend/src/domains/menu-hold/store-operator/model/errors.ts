import { isApiError, isNetworkError } from '../../../../shared/api/apiError'

export function inventoryErrorMessage(error: unknown): string {
  if (isNetworkError(error)) return '처리 결과를 확인하지 못했습니다. 최신 재고를 다시 조회해 주세요.'
  if (!isApiError(error)) return '재고 요청을 처리하지 못했습니다.'
  switch (error.code) {
    case 'MENU_HOLD_004': return '세 풀의 합계가 총 공급과 일치하지 않습니다.'
    case 'MENU_HOLD_005': return '이미 사용된 수량보다 공급량을 작게 줄일 수 없습니다.'
    case 'MENU_HOLD_006': return '현재 재고 상태에서는 요청한 변경을 할 수 없습니다.'
    case 'STORE_003': return '이 매장의 재고를 관리할 권한이 없습니다.'
    default: return error.status === 409 ? '다른 재고 변경과 충돌했습니다. 최신 상태를 다시 확인해 주세요.' : '재고 요청을 처리하지 못했습니다.'
  }
}
