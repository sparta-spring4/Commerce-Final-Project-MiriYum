import type { components } from '../../../../shared/api/generated/waiting'

/**
 * 웨이팅 화면이 쓰는 타입은 전부 활성 Waiting OpenAPI 생성물에서 온다.
 *
 * 응답 필드를 손으로 다시 선언하지 않는다. 계약이 바뀌면 typecheck가 먼저
 * 깨져야 하고, 화면이 임의로 만든 모양이 그 신호를 가리면 안 된다.
 */

export type WaitingSetting = components['schemas']['WaitingSetting']
export type WaitingSettingUpdateRequest =
  components['schemas']['WaitingSettingUpdateRequest']
export type WaitingReceptionMode =
  components['schemas']['WaitingReceptionMode']
export type WaitingDisableAction =
  components['schemas']['WaitingDisableAction']
export type WaitingDisableImpact =
  components['schemas']['WaitingDisableImpact']

export type WaitingTeamStatus = components['schemas']['WaitingTeamStatus']
export type WaitingTeamListItem = components['schemas']['WaitingTeamListItem']
export type WaitingTeamDetail = components['schemas']['WaitingTeamDetail']
export type WaitingTeamPage = components['schemas']['WaitingTeamPage']

export type WaitingClosureJob = components['schemas']['WaitingClosureJob']
export type WaitingClosureJobStatus =
  components['schemas']['WaitingClosureJobStatus']

export const RECEPTION_MODE_LABEL: Record<WaitingReceptionMode, string> = {
  AUTO: '자동 접수',
  MANUAL: '수동 접수',
  PAUSED: '신규 접수 중지',
}

export const RECEPTION_MODE_HINT: Record<WaitingReceptionMode, string> = {
  AUTO: '영업 시작 전 지정한 시간부터 접수를 자동으로 엽니다.',
  MANUAL: '운영자가 직접 열어야 접수를 받습니다.',
  PAUSED: '이미 등록된 팀은 유지하고 신규 접수만 막습니다.',
}

export const RECEPTION_MODES: readonly WaitingReceptionMode[] = [
  'AUTO',
  'MANUAL',
  'PAUSED',
]

export const TEAM_STATUS_LABEL: Record<WaitingTeamStatus, string> = {
  WAITING: '대기 중',
  CALLED: '호출됨',
  ARRIVED: '도착',
  CHECKED_IN: '입장 완료',
  CANCELLED: '취소됨',
  NO_SHOW: '미응답',
  CLOSED_BY_STORE: '매장 종결',
  RESERVATION_CONVERTING: '예약 전환 중',
  RESERVATION_CONVERTED: '예약 전환 완료',
}

/** 목록 필터로 노출하는 상태. 계약의 enum 전체를 그대로 쓴다. */
export const TEAM_STATUSES: readonly WaitingTeamStatus[] = [
  'WAITING',
  'CALLED',
  'ARRIVED',
  'CHECKED_IN',
  'CANCELLED',
  'NO_SHOW',
  'CLOSED_BY_STORE',
  'RESERVATION_CONVERTING',
  'RESERVATION_CONVERTED',
]

export const CLOSURE_JOB_STATUS_LABEL: Record<WaitingClosureJobStatus, string> =
  {
    PENDING: '대기 중',
    PROCESSING: '처리 중',
    COMPLETED: '완료',
    RECONCILIATION_REQUIRED: '대사 필요',
  }

/**
 * 종결 작업이 더 진행되지 않는 상태인지 판정한다.
 *
 * 백엔드는 대상 팀을 다 처리한 순간에만 `COMPLETED` 또는
 * `RECONCILIATION_REQUIRED`로 넘기고 그 뒤로는 상태를 바꾸지 않는다.
 * `PENDING`과 `PROCESSING`은 서로 오가므로 둘 다 진행 중으로 본다.
 *
 * 재조회를 언제 멈출지가 이 판정 하나에 달려 있다. 진행 중을 종결로 잘못 보면
 * 화면이 최초 상태에 멈추고, 반대로 보면 끝난 작업을 계속 폴링한다.
 */
export function isClosureJobSettled(job: WaitingClosureJob): boolean {
  return job.status === 'COMPLETED' || job.status === 'RECONCILIATION_REQUIRED'
}

/** 종결 작업에 사람이 개입해야 하는지. 실패·대사 대상이 하나라도 있으면 참이다. */
export function needsClosureFollowUp(job: WaitingClosureJob): boolean {
  return job.failedTeamCount > 0 || job.reconciliationRequiredTeamCount > 0
}

/**
 * 비활성화 응답이 설정인지 종결 작업인지 가른다.
 *
 * client가 HTTP status를 노출하지 않으므로 응답 모양으로 판정한다. 두 스키마는
 * `additionalProperties: false`라 필드가 섞이지 않고, `jobId`는 종결 작업에만 있다.
 */
export function isClosureJob(
  data: WaitingSetting | WaitingClosureJob,
): data is WaitingClosureJob {
  return 'jobId' in data
}
