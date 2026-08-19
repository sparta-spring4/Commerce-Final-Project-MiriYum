import type { BadgeTone } from '../../../../shared/ui/Badge'
import type {
  AuditReason,
  GrantablePermission,
  GrantableRole,
  OperatorAccountStatus,
  OperatorPermission,
  OperatorRole,
} from '../api/operatorAccountApi'

/**
 * 운영자 계정 화면의 표시명.
 *
 * 목록·상세·폼이 같은 값을 쓴다. 화면마다 따로 두면 한쪽만 갱신돼
 * 같은 역할이 두 화면에서 다르게 읽힌다.
 */

export const OPERATOR_STATUS_LABEL: Record<OperatorAccountStatus, string> = {
  ACTIVE: '활성',
  SUSPENDED: '중지됨',
}

export const OPERATOR_STATUS_TONE: Record<OperatorAccountStatus, BadgeTone> = {
  ACTIVE: 'positive',
  SUSPENDED: 'negative',
}

/**
 * 조회에 나타날 수 있는 전체 역할.
 *
 * `SUPER_ADMIN`이 포함되지만 부여 가능한 역할은 아니다. 부트스트랩으로
 * 존재하는 단일 슈퍼관리자가 목록·상세에 보이므로 표시명은 필요하다.
 */
export const OPERATOR_ROLE_LABEL: Record<OperatorRole, string> = {
  SUPER_ADMIN: '슈퍼관리자',
  ONBOARDING_REVIEWER: '입점 심사',
  MEMBER_SUPPORT_OPERATOR: '회원지원',
  ENFORCEMENT_OPERATOR: '제재 집행',
  PAYMENT_RECOVERY_OPERATOR: '결제 복구',
  OPERATIONS_MONITOR: '운영 모니터링',
  AUDIT_READER: '감사 조회',
  INCIDENT_RESPONDER: '장애 대응',
}

export const OPERATOR_PERMISSION_LABEL: Record<OperatorPermission, string> = {
  OPERATOR_CREATE: '운영자 생성',
  OPERATOR_AUTHORITY_MANAGE: '운영자 권한 관리',
  OPERATOR_SUSPEND: '운영자 중지',
  ONBOARDING_REVIEW: '입점 심사',
  ONBOARDING_EVIDENCE_READ: '입점 증빙 조회',
  MEMBER_READ_MINIMAL: '회원 최소 조회',
  MEMBER_RECOVERY: '회원 복구',
  ACCOUNT_SANCTION: '계정 제재',
  ACCOUNT_PERMANENT_SANCTION_APPROVE: '영구 정지 승인',
  ACCOUNT_APPEAL_REVIEW: '이의 심사',
  STORE_READ_MINIMAL: '매장 최소 조회',
  STORE_SANCTION: '매장 제재',
  OPERATIONS_MONITOR_READ: '운영 모니터링 조회',
  PAYMENT_RECOVERY_EXECUTE: '결제 복구 실행',
  PAYMENT_RECOVERY_HIGH_VALUE_APPROVE: '고액 결제 복구 승인',
  AUDIT_READ: '감사 조회',
  INCIDENT_RESPOND: '장애 대응',
  BREAK_GLASS_APPROVE: '비상 승인',
}

/**
 * 폼이 제시할 수 있는 역할·권한.
 *
 * 계약이 생성·권한 교체 요청에 `SUPER_ADMIN`과 핵심 권한을 받지 않는다.
 * 전체 목록에서 걸러내지 않고 계약 타입을 그대로 나열해, 계약이 넓어지면
 * typecheck가 누락을 잡게 한다.
 */
export const GRANTABLE_ROLES: readonly GrantableRole[] = [
  'ONBOARDING_REVIEWER',
  'MEMBER_SUPPORT_OPERATOR',
  'ENFORCEMENT_OPERATOR',
  'PAYMENT_RECOVERY_OPERATOR',
  'OPERATIONS_MONITOR',
  'AUDIT_READER',
  'INCIDENT_RESPONDER',
]

export const GRANTABLE_PERMISSIONS: readonly GrantablePermission[] = [
  'ONBOARDING_REVIEW',
  'ONBOARDING_EVIDENCE_READ',
  'MEMBER_READ_MINIMAL',
  'MEMBER_RECOVERY',
  'ACCOUNT_SANCTION',
  'ACCOUNT_APPEAL_REVIEW',
  'STORE_READ_MINIMAL',
  'STORE_SANCTION',
  'OPERATIONS_MONITOR_READ',
  'PAYMENT_RECOVERY_EXECUTE',
  'AUDIT_READ',
  'INCIDENT_RESPOND',
]

/** 관리 명령의 사유 코드. 계약이 정한 값만 쓴다. */
export const OPERATOR_REASON_LABEL: Record<AuditReason, string> = {
  AUTHENTICATION_EVENT: '인증 사건',
  ACCOUNT_PROVISIONING: '계정 발급',
  RESPONSIBILITY_CHANGE: '담당 변경',
  EMPLOYMENT_END: '퇴직 처리',
  SECURITY_RESPONSE: '보안 대응',
  AUDIT_VERIFICATION: '감사 검증',
  RECORD_CORRECTION: '기록 보정',
  STORE_ENFORCEMENT: '매장 제재',
}

/**
 * 계정 관리 명령에서 고를 수 있는 사유.
 *
 * 감사 보정·매장 제재처럼 다른 업무의 사유는 여기서 제시하지 않는다.
 * 계약이 enum을 공유할 뿐 이 명령의 사유는 아니다.
 */
export const OPERATOR_COMMAND_REASONS: readonly AuditReason[] = [
  'ACCOUNT_PROVISIONING',
  'RESPONSIBILITY_CHANGE',
  'EMPLOYMENT_END',
  'SECURITY_RESPONSE',
]

/** 역할이 자동으로 주는 권한과 직접 부여된 권한을 구분해 보여 주기 위한 판정. */
export function isDirectPermission(
  permission: OperatorPermission,
  directPermissions: readonly OperatorPermission[],
): boolean {
  return directPermissions.includes(permission)
}
