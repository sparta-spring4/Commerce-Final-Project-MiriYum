import type { ApiClient } from '../../../shared/api/client'
import type { components } from '../../../shared/api/generated/platform-operator-management-audit'
import { PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS } from '../../../shared/api/platformOperatorSession'

export type AuditEventData = components['schemas']['AuditEventData']
export type EventSource = components['schemas']['EventSource']
export type AuditAction = components['schemas']['AuditAction']
export type AuditOutcome = components['schemas']['AuditOutcome']
export type AuditReason = components['schemas']['AuditReason']

/**
 * 감사 조회의 인가 맥락.
 *
 * 계약이 조회 자체에 `X-Admin-Case-Id`·`X-Admin-Case-Version`·`X-Admin-Reason-Code`를
 * 요구한다. 감사 조회는 그냥 읽기가 아니라 배정받은 `AUDIT_REVIEW` 사건 안에서
 * 사유를 남기고 하는 행위이며, 허용된 조회와 거부된 조회가 모두 원장에 append된다.
 *
 * 그래서 이 값들은 화면이 운영자에게서 받아야 한다. 기본값을 넣거나 자동 생성하면
 * 감사 원장에 거짓 사유가 남는다. 시안에는 이 입력이 없지만 계약이 필수로 둔다.
 */
export interface AuditReviewContext {
  caseId: string
  caseVersion: number
  reasonCode: AuditReason
}

export interface AuditSearchQuery {
  source?: EventSource
  action?: AuditAction
  outcome?: AuditOutcome
  actorOperatorId?: string
  targetType?: string
  targetId?: string
  occurredFrom?: string
  occurredTo?: string
  page: number
  size: number
}

export const auditQueryKeys = {
  search: (context: AuditReviewContext, query: AuditSearchQuery) =>
    [
      ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.auditEvents,
      // 사건 맥락이 바뀌면 다른 조회다. key에 넣지 않으면 사건 A의 결과가
      // 사건 B 화면에 그대로 보인다.
      context.caseId,
      context.caseVersion,
      context.reasonCode,
      query,
    ] as const,
  detail: (context: AuditReviewContext, eventKey: string) =>
    [
      ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.auditEvents,
      context.caseId,
      context.caseVersion,
      context.reasonCode,
      eventKey,
    ] as const,
}

export async function searchAuditEvents(
  apiClient: ApiClient,
  context: AuditReviewContext,
  query: AuditSearchQuery,
  signal?: AbortSignal,
) {
  const response = await apiClient('/api/v1/platform-operators/audit-events', {
    method: 'get',
    adminAuditContext: {
      caseId: context.caseId,
      caseVersion: context.caseVersion,
      reasonCode: context.reasonCode,
    },
    query: {
      source: query.source,
      action: query.action,
      outcome: query.outcome,
      actorOperatorId: query.actorOperatorId,
      targetType: query.targetType,
      targetId: query.targetId,
      occurredFrom: query.occurredFrom,
      occurredTo: query.occurredTo,
      page: query.page,
      size: query.size,
    },
    signal,
  })
  return response.data
}

export async function fetchAuditEvent(
  apiClient: ApiClient,
  context: AuditReviewContext,
  eventKey: string,
  signal?: AbortSignal,
) {
  const response = await apiClient(
    '/api/v1/platform-operators/audit-events/{eventKey}',
    {
      method: 'get',
      pathParams: { eventKey },
      adminAuditContext: {
        caseId: context.caseId,
        caseVersion: context.caseVersion,
        reasonCode: context.reasonCode,
      },
      signal,
    },
  )
  return response.data
}

export type CorrectionRequest = components['schemas']['CorrectionRequest']

/**
 * 보정 사건 추가.
 *
 * 원 사건을 수정하지 않고 연결된 새 사건을 append한다. 그래서 응답은
 * 원 사건이 아니라 새로 만들어진 보정 사건이다.
 *
 * 조회 헤더(사건·version·사유)에 더해 재인증 승인과 멱등 키까지 요구한다.
 * 보정은 조회가 아니라 고위험 명령이기 때문이다.
 */
export async function createAuditCorrection(
  apiClient: ApiClient,
  input: {
    eventKey: string
    context: AuditReviewContext
    reauthenticationApproval: string
    idempotencyKey: string
    body: CorrectionRequest
  },
) {
  const response = await apiClient(
    '/api/v1/platform-operators/audit-events/{eventKey}/corrections',
    {
      method: 'post',
      pathParams: { eventKey: input.eventKey },
      body: input.body,
      // 보정은 조회와 달리 사유 코드 헤더를 요구하지 않는다.
      // 사유는 본문의 `reason: RECORD_CORRECTION` 고정값이 담당한다.
      adminCaseRef: {
        caseId: input.context.caseId,
        caseVersion: input.context.caseVersion,
      },
      adminReauthentication: input.reauthenticationApproval,
      idempotencyKey: input.idempotencyKey,
    },
  )
  return response.data
}
