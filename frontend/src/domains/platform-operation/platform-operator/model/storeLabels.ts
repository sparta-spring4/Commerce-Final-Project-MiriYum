import type { BadgeTone } from '../../../../shared/ui/Badge'
import type {
  StoreOperationStatus,
  StoreRestrictedFeature,
  StoreSanctionCaseStatus,
  StoreSanctionStatus,
  StoreSanctionType,
} from '../api/storeAdminApi'

/**
 * 매장 관리 화면의 표시명.
 *
 * 목록·상세·사건 화면이 같은 값을 쓴다. 화면마다 따로 두면 한쪽만 갱신돼
 * 같은 상태가 두 화면에서 다르게 읽힌다.
 */

export const STORE_OPERATION_STATUS_LABEL: Record<
  StoreOperationStatus,
  string
> = {
  OPEN: '운영 중',
  TEMPORARILY_CLOSED: '임시 휴업',
  CLOSED: '운영 종료',
}

export const STORE_OPERATION_STATUS_TONE: Record<
  StoreOperationStatus,
  BadgeTone
> = {
  OPEN: 'positive',
  TEMPORARILY_CLOSED: 'attention',
  CLOSED: 'neutral',
}

export const STORE_SANCTION_TYPE_LABEL: Record<StoreSanctionType, string> = {
  WARNING: '경고',
  FEATURE_RESTRICTION: '기능 제한',
  TEMPORARY_SUSPENSION: '기간 정지',
  PERMANENT_EXIT: '영구 퇴점',
}

export const STORE_SANCTION_TYPE_TONE: Record<StoreSanctionType, BadgeTone> = {
  WARNING: 'attention',
  FEATURE_RESTRICTION: 'attention',
  TEMPORARY_SUSPENSION: 'negative',
  PERMANENT_EXIT: 'negative',
}

export const STORE_SANCTION_STATUS_LABEL: Record<StoreSanctionStatus, string> =
  {
    PENDING_APPROVAL: '승인 대기',
    ACTIVE: '적용 중',
    RELEASED: '해제됨',
    EXPIRED: '기간 만료',
    REJECTED: '반려',
  }

export const STORE_SANCTION_STATUS_TONE: Record<
  StoreSanctionStatus,
  BadgeTone
> = {
  PENDING_APPROVAL: 'attention',
  ACTIVE: 'negative',
  RELEASED: 'positive',
  EXPIRED: 'neutral',
  REJECTED: 'neutral',
}

export const STORE_CASE_STATUS_LABEL: Record<StoreSanctionCaseStatus, string> =
  {
    SUBMITTED: '접수',
    ASSIGNED: '배정됨',
    PENDING_APPROVAL: '승인 대기',
    ACTIVE: '제재 적용 중',
    RESOLVED: '종결',
    REJECTED: '반려',
  }

export const STORE_CASE_STATUS_TONE: Record<
  StoreSanctionCaseStatus,
  BadgeTone
> = {
  SUBMITTED: 'attention',
  ASSIGNED: 'neutral',
  PENDING_APPROVAL: 'attention',
  ACTIVE: 'negative',
  RESOLVED: 'positive',
  REJECTED: 'neutral',
}

export const STORE_FEATURE_LABEL: Record<StoreRestrictedFeature, string> = {
  RESERVATION: '예약',
  WAITING: '웨이팅',
  MENU_HOLD: '메뉴 홀드',
  PICKUP: '픽업',
  STORE_MANAGEMENT: '매장 관리',
}

export const STORE_RESTRICTED_FEATURES: readonly StoreRestrictedFeature[] = [
  'RESERVATION',
  'WAITING',
  'MENU_HOLD',
  'PICKUP',
  'STORE_MANAGEMENT',
]

export const STORE_SANCTION_TYPES: readonly StoreSanctionType[] = [
  'WARNING',
  'FEATURE_RESTRICTION',
  'TEMPORARY_SUSPENSION',
  'PERMANENT_EXIT',
]

/**
 * 고위험 제재 판정.
 *
 * 계약이 재인증 헤더를 optional로 두고 "고위험 제재 생성일 때 필수"라고만
 * 적는다. 어떤 조합이 고위험인지는 `admin-store/spec.md`가 정하며,
 * 기간 정지·영구 퇴점과 전체 기능 제한이 여기 해당한다.
 *
 * 화면이 판정을 좁게 잡으면 서버가 거절하고, 넓게 잡으면 경고 제재까지
 * 불필요한 재인증을 거친다. 어느 쪽도 조용히 실패하지 않도록 서버가 요구할
 * 수 있는 경우를 모두 포함한다.
 */
export function isHighRiskStoreSanction(
  type: StoreSanctionType,
  restrictedFeatures: readonly StoreRestrictedFeature[],
): boolean {
  if (type === 'TEMPORARY_SUSPENSION' || type === 'PERMANENT_EXIT') {
    return true
  }
  return restrictedFeatures.length >= STORE_RESTRICTED_FEATURES.length
}

/** 영구 퇴점은 일반 해제로 되돌릴 수 없다. */
export function isReleasableSanction(
  type: StoreSanctionType,
  status: StoreSanctionStatus,
): boolean {
  return type !== 'PERMANENT_EXIT' && status === 'ACTIVE'
}
