import { isApiError, isNetworkError } from '../../../shared/api/apiError'
import { CommonErrorCode } from '../../../shared/api/envelope'
import { AuthErrorCode } from '../../auth/model/authErrors'
import { StoreErrorCode } from '../../store-operator/model/storeErrors'

/**
 * 예약 도메인 오류 코드.
 *
 * backend `ReservationErrorCode`와 1:1로 맞춘다. 매장 접근 거부·매장 없음은
 * 예약 계약에서도 store 코드를 그대로 쓰므로 그쪽 정의를 재사용한다.
 */
export const ReservationErrorCode = {
  NOT_FOUND: 'RESERVATION_001',
  OUTSIDE_WINDOW: 'RESERVATION_002',
  INSUFFICIENT_CAPACITY: 'RESERVATION_003',
  DUPLICATE: 'RESERVATION_004',
  INVALID_STATE: 'RESERVATION_005',
  CANCELLATION_NOT_ALLOWED: 'RESERVATION_006',
  POLICY_VERSION_CHANGED: 'RESERVATION_007',
  CAPACITY_CONFIGURATION_CONFLICT: 'RESERVATION_008',
  PARTY_SIZE_OUT_OF_RANGE: 'RESERVATION_009',
  TIME_POLICY_CONFLICT: 'RESERVATION_010',
} as const

/**
 * 서버 오류를 운영자용 문구로 옮긴다.
 *
 * 경쟁 상태를 그대로 노출하지 않는다. `RESERVATION_008`은 "지금 몇 명이 잡고
 * 있다"가 아니라 "현재 점유와 충돌한다"로만 알린다.
 */
export function reservationOpsErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 처리 여부가 확정되지 않았으니 상태를 다시 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case ReservationErrorCode.NOT_FOUND:
      return '예약을 찾을 수 없습니다.'
    case ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT:
      return '이미 접수된 예약과 수용량 설정이 충돌합니다. 값을 낮추기 전에 예약 현황을 확인해 주세요.'
    case ReservationErrorCode.PARTY_SIZE_OUT_OF_RANGE:
      return '설정한 최소·최대 인원 범위를 벗어납니다.'
    case ReservationErrorCode.TIME_POLICY_CONFLICT:
      return '현재 시간 정책 상태에서는 이 작업을 할 수 없습니다.'
    case ReservationErrorCode.POLICY_VERSION_CHANGED:
      return '조회 후 정책·수용량 버전이 바뀌었습니다. 최신 상태를 다시 확인해 주세요.'
    case ReservationErrorCode.INVALID_STATE:
      return '현재 예약 상태에서는 처리할 수 없습니다. 목록을 새로 조회해 주세요.'
    case ReservationErrorCode.CANCELLATION_NOT_ALLOWED:
      return '지금은 취소 정책상 이 예약을 취소할 수 없습니다.'
    case AuthErrorCode.ACCOUNT_RESTRICTED:
      return '현재 계정 상태로는 예약을 처리할 수 없습니다. 고객센터에 문의해 주세요.'
    case StoreErrorCode.NOT_FOUND:
      return '매장을 찾을 수 없습니다.'
    case StoreErrorCode.ACCESS_DENIED:
      return '이 매장의 대표 운영자가 아닙니다.'
    case CommonErrorCode.VALIDATION_FAILED:
      return '입력한 내용을 다시 확인해 주세요.'
    case CommonErrorCode.CONCURRENT_MODIFICATION:
      return '다른 변경과 겹쳤습니다. 최신 내용을 다시 확인해 주세요.'
    case CommonErrorCode.IDEMPOTENCY_KEY_REUSED:
      return '이전 요청과 같은 키로 다른 내용을 보냈습니다. 화면을 새로 열어 다시 시도해 주세요.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    case CommonErrorCode.SERVICE_UNAVAILABLE:
      return '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
    default:
      return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
