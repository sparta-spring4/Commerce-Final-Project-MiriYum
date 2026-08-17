import { http } from 'msw'
import { successResponse } from '../../../test/msw/envelope'
import { STORE_ID } from '../../store-operator/test/handlers'
import type {
  WaitingClosureJob,
  WaitingDisableImpact,
  WaitingSetting,
  WaitingTeamDetail,
  WaitingTeamListItem,
  WaitingTeamPage,
} from '../model/types'

/**
 * 웨이팅 화면 테스트용 MSW 경로와 고정 데이터.
 *
 * 경로 문자열을 여기 모아 두어 화면 테스트가 계약과 다른 URL을 쓰는 실수를
 * 한 곳에서 막는다.
 */

const BASE = `/api/v1/store-operators/stores/${STORE_ID}`

export const WAITING_SETTINGS_PATH = `${BASE}/waiting-settings`
export const WAITING_IMPACT_PATH = `${BASE}/waiting-settings/deactivation-impact`
export const WAITING_TEAMS_PATH = `${BASE}/waiting-teams`
export const waitingTeamPath = (id: string) => `${BASE}/waiting-teams/${id}`
export const waitingCommandPath = (id: string, command: string) =>
  `${BASE}/waiting-teams/${id}/${command}`

export function waitingSetting(
  overrides: Partial<WaitingSetting> = {},
): WaitingSetting {
  return {
    storeId: STORE_ID,
    enabled: true,
    receptionMode: 'AUTO',
    advanceOpenMinutes: 30,
    version: 3,
    ...overrides,
  }
}

export function disableImpact(
  overrides: Partial<WaitingDisableImpact> = {},
): WaitingDisableImpact {
  return {
    storeId: STORE_ID,
    version: 3,
    activeTeamCount: 2,
    ...overrides,
  }
}

export function closureJob(
  overrides: Partial<WaitingClosureJob> = {},
): WaitingClosureJob {
  return {
    jobId: '900',
    storeId: STORE_ID,
    status: 'PROCESSING',
    totalTeamCount: 2,
    completedTeamCount: 1,
    failedTeamCount: 0,
    reconciliationRequiredTeamCount: 0,
    createdAt: '2026-08-20T01:00:00Z',
    completedAt: null,
    ...overrides,
  }
}

export function teamItem(
  overrides: Partial<WaitingTeamListItem> = {},
): WaitingTeamListItem {
  return {
    waitingTeamId: '11',
    status: 'WAITING',
    queueSequence: 1,
    partySize: 2,
    createdAt: '2026-08-20T01:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function teamDetail(
  overrides: Partial<WaitingTeamDetail> = {},
): WaitingTeamDetail {
  return {
    waitingTeamId: '11',
    storeId: STORE_ID,
    status: 'WAITING',
    queueSequence: 1,
    partySize: 2,
    createdAt: '2026-08-20T01:00:00Z',
    calledAt: null,
    arrivedAt: null,
    checkedInAt: null,
    cancelledAt: null,
    version: 0,
    ...overrides,
  }
}

export function teamPage(
  items: readonly WaitingTeamListItem[],
  nextCursor: string | null = null,
): WaitingTeamPage {
  return { items: [...items], nextCursor }
}

/** 설정 조회 기본 핸들러. 화면마다 필요한 것만 골라 덮어쓴다. */
export function waitingSettingsHandler(setting = waitingSetting()) {
  return http.get(WAITING_SETTINGS_PATH, () => successResponse(setting))
}

export function waitingTeamsHandler(page = teamPage([teamItem()])) {
  return http.get(WAITING_TEAMS_PATH, () => successResponse(page))
}

export function waitingTeamHandler(detail = teamDetail()) {
  return http.get(waitingTeamPath(detail.waitingTeamId), () =>
    successResponse(detail),
  )
}
