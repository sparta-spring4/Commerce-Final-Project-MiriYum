import { isApiError, isNetworkError } from '../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../shared/api/envelope'

/**
 * 매장·메뉴 도메인 오류 코드.
 *
 * backend `StoreErrorCode`와 1:1로 맞춘다. 메뉴 오류도 같은 enum에 있어
 * `STORE_009`·`STORE_010`을 쓴다. 문서에 보이는 `STORE_008`(픽업 자격)은 현재
 * 계약에 없다. 업종으로 픽업을 막지 않기로 정리됐으므로 클라이언트가 그 조합을
 * 미리 거절하거나 값을 자동 보정하지도 않는다.
 */
export const StoreErrorCode = {
  NOT_FOUND: 'STORE_001',
  BUSINESS_NUMBER_CONFLICT: 'STORE_002',
  ACCESS_DENIED: 'STORE_003',
  CATALOG_CODE_INVALID: 'STORE_004',
  STATE_CONFLICT: 'STORE_005',
  SCHEDULE_CONFLICT: 'STORE_006',
  VERIFICATION_STATE_CONFLICT: 'STORE_007',
  MENU_NOT_FOUND: 'STORE_009',
  MENU_STATE_CONFLICT: 'STORE_010',
  PUBLIC_IMAGE_NOT_FOUND: 'STORE_011',
  PUBLIC_IMAGE_LIMIT_EXCEEDED: 'STORE_012',
} as const

/**
 * 서버 오류를 운영자용 문구로 옮긴다.
 *
 * 서버 message를 그대로 노출하지 않고 코드로 분기한다. 코드를 모르면 서버가 준
 * 문구를 쓰되, 내부 예외 이름이 새어 나오지 않도록 기본 문구로 대체한다.
 */
export function storeErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 처리 여부가 확정되지 않았으니 상태를 다시 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.status === 413) {
    return '이미지 파일은 10MB 이하만 등록할 수 있습니다.'
  }
  if (error.status === 415) {
    return 'JPG, PNG, WEBP 이미지 파일만 등록할 수 있습니다.'
  }
  switch (error.code) {
    case StoreErrorCode.NOT_FOUND:
      return '매장을 찾을 수 없습니다.'
    case StoreErrorCode.BUSINESS_NUMBER_CONFLICT:
      return '이미 등록된 사업자등록번호입니다.'
    case StoreErrorCode.ACCESS_DENIED:
      return '이 매장의 대표 운영자가 아닙니다.'
    case StoreErrorCode.CATALOG_CODE_INVALID:
      return '승인되지 않은 카테고리 또는 태그입니다. 목록에서 다시 선택해 주세요.'
    case StoreErrorCode.STATE_CONFLICT:
      return '현재 매장 상태에서는 이 작업을 할 수 없습니다.'
    case StoreErrorCode.SCHEDULE_CONFLICT:
      return '영업시간 또는 예약 접수 시간대가 충돌합니다. 구간을 확인해 주세요.'
    case StoreErrorCode.VERIFICATION_STATE_CONFLICT:
      return '현재 입점 상태에서는 운영할 수 없습니다.'
    case StoreErrorCode.MENU_NOT_FOUND:
      return '메뉴를 찾을 수 없습니다.'
    case StoreErrorCode.MENU_STATE_CONFLICT:
      return '현재 메뉴 상태에서는 이 전이를 할 수 없습니다.'
    case StoreErrorCode.PUBLIC_IMAGE_NOT_FOUND:
      return '이미지를 찾을 수 없습니다. 목록을 새로고침한 뒤 다시 시도해 주세요.'
    case StoreErrorCode.PUBLIC_IMAGE_LIMIT_EXCEEDED:
      return '매장 이미지는 최대 10장까지 등록할 수 있습니다.'
    case CommonErrorCode.VALIDATION_FAILED:
      return '입력한 내용을 다시 확인해 주세요.'
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '이전 요청과 같은 키로 다른 내용을 보냈습니다. 화면을 새로 열어 다시 시도해 주세요.'
    case CommonErrorCode.CONCURRENT_MODIFICATION:
      return '다른 변경과 겹쳤습니다. 최신 내용을 다시 확인해 주세요.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    case CommonErrorCode.SERVICE_UNAVAILABLE:
      return '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
    default:
      return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
