import type { components } from '../../../shared/api/generated/reservation'

/**
 * 예약 운영 화면이 쓰는 계약 타입.
 * 생성 타입에서만 가져오고 응답 필드를 손으로 다시 정의하지 않는다.
 */

export type CapacityBucketRequest =
  components['schemas']['CapacityBucketRequest']
export type CapacityBucket = components['schemas']['CapacityBucket']
export type ReservationCapacitiesData =
  components['schemas']['ReservationCapacitiesData']

export type ReservationTimePolicyDraftRequest =
  components['schemas']['ReservationTimePolicyDraftRequest']
export type ReservationTimePolicyResponse =
  components['schemas']['ReservationTimePolicyResponse']
export type ReservationTimePolicyStatus =
  components['schemas']['ReservationTimePolicyStatus']

export type ReservationStatus = components['schemas']['ReservationStatus']
export type ReservationSummary = components['schemas']['ReservationSummary']
export type ReservationDetail = components['schemas']['ReservationDetail']
export type ReservationPageData = components['schemas']['ReservationPageData']

/** 목록 정렬. 계약이 허용한 조합만 노출한다. */
export type ReservationSort =
  | 'serviceDate,asc'
  | 'serviceDate,desc'
  | 'createdAt,asc'
  | 'createdAt,desc'

/**
 * 예약 상태 문구.
 *
 * `NO_SHOW`는 운영자가 누르는 상태가 아니다. 서버가 `startAt + 5분`에 미방문을
 * 원자 확정하며, 이 화면에는 결과만 내려온다. 그래서 처리 패널에 버튼을 만들지
 * 않고 목록·상세의 표시와 필터에만 넣는다.
 */
export const RESERVATION_STATUS_LABEL: Record<ReservationStatus, string> = {
  CONFIRMED: '예약 확정',
  CANCELLED: '취소됨',
  FULFILLED: '방문 완료',
  NO_SHOW: '노쇼',
}

export const RESERVATION_STATUSES: readonly ReservationStatus[] = [
  'CONFIRMED',
  'CANCELLED',
  'FULFILLED',
  'NO_SHOW',
]

export const RESERVATION_SORT_LABEL: Record<ReservationSort, string> = {
  'serviceDate,asc': '이용일 오름차순',
  'serviceDate,desc': '이용일 내림차순',
  'createdAt,asc': '접수일 오름차순',
  'createdAt,desc': '접수일 내림차순',
}

export const POLICY_STATUS_LABEL: Record<ReservationTimePolicyStatus, string> = {
  DRAFT: '초안',
  SCHEDULED: '게시 예약',
  ACTIVE: '게시됨',
  RETIRED: '종료',
  ACTIVATION_FAILED: '게시 실패',
}

/** 취소 주체. 계약이 두 값과 null만 허용한다. */
export const CANCELLED_BY_LABEL: Record<'CONSUMER' | 'STORE_OPERATOR', string> =
  {
    CONSUMER: '고객 취소',
    STORE_OPERATOR: '매장 취소',
  }
