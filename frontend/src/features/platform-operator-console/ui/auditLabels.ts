import type { BadgeTone } from '../../../shared/ui/Badge'
import type {
  AuditOutcome,
  AuditReason,
  EventSource,
} from '../api/auditApi'

/**
 * 감사 화면 공통 표시명.
 *
 * 검색과 상세가 같은 값을 쓴다. 화면마다 따로 두면 한쪽만 갱신돼
 * 같은 사건이 두 화면에서 다르게 읽힌다.
 */

export const OUTCOME_LABEL: Record<AuditOutcome, string> = {
  SUCCESS: '성공',
  DENIED: '거부',
  FAILED: '실패',
}

export const OUTCOME_TONE: Record<AuditOutcome, BadgeTone> = {
  SUCCESS: 'positive',
  DENIED: 'attention',
  FAILED: 'negative',
}

export const SOURCE_LABEL: Record<EventSource, string> = {
  AUTH: '인증',
  ADMIN: '관리 명령',
}

/** 계약이 정한 조회 사유 코드. 자유 입력이 아니다. */
export const REASON_LABEL: Record<AuditReason, string> = {
  AUTHENTICATION_EVENT: '인증 사건 확인',
  ACCOUNT_PROVISIONING: '계정 발급 확인',
  RESPONSIBILITY_CHANGE: '담당 변경 확인',
  EMPLOYMENT_END: '퇴직 처리 확인',
  SECURITY_RESPONSE: '보안 대응',
  AUDIT_VERIFICATION: '감사 검증',
  RECORD_CORRECTION: '기록 보정',
  // #279 매장 제재가 추가한 사유다. 매장 제재 화면 자체는 아직 범위가 아니지만,
  // 감사 조회는 그 사유로 기록된 사건도 함께 읽으므로 표시명이 필요하다.
  STORE_ENFORCEMENT: '매장 제재',
}
