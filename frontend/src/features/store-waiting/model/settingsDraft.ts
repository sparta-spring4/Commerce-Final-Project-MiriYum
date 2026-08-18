import type {
  WaitingDisableAction,
  WaitingReceptionMode,
  WaitingSetting,
  WaitingSettingUpdateRequest,
} from './types'

/**
 * 웨이팅 설정 초안.
 *
 * 계약의 PUT은 전체 교체이며 `expectedVersion`을 함께 받는다. 그래서 초안은
 * 조회한 설정 하나를 통째로 들고 다니고, 저장할 때 그 시점의 version을 붙인다.
 */
export interface WaitingSettingsDraft {
  enabled: boolean
  receptionMode: WaitingReceptionMode
  advanceOpenMinutes: number
}

export const ADVANCE_OPEN_MINUTES_MIN = 0
export const ADVANCE_OPEN_MINUTES_MAX = 180

export function toDraft(setting: WaitingSetting): WaitingSettingsDraft {
  return {
    enabled: setting.enabled,
    receptionMode: setting.receptionMode,
    advanceOpenMinutes: setting.advanceOpenMinutes,
  }
}

/**
 * 사용 여부를 바꾼다.
 *
 * 계약은 `enabled=false`면 `receptionMode`가 반드시 `PAUSED`라고 못박는다.
 * 화면에서 두 값을 따로 두면 운영자가 모순된 조합을 만들고 서버가 거절한다.
 * 끄는 순간 접수 모드를 함께 내린다. 다시 켤 때는 운영자가 고르게 두되,
 * `PAUSED`로 남아 있으면 그대로 둔다. 켜면서 신규 접수만 막는 것은 유효한 조합이다.
 */
export function setEnabled(
  draft: WaitingSettingsDraft,
  enabled: boolean,
): WaitingSettingsDraft {
  if (!enabled) {
    return { ...draft, enabled: false, receptionMode: 'PAUSED' }
  }
  return { ...draft, enabled: true }
}

/**
 * 접수 모드를 바꾼다.
 *
 * 꺼진 상태에서 `PAUSED`가 아닌 모드를 고르는 것은 계약이 막는 조합이다.
 * 그 선택은 "다시 켜겠다"는 뜻이므로 사용 여부도 함께 올린다.
 */
export function setReceptionMode(
  draft: WaitingSettingsDraft,
  receptionMode: WaitingReceptionMode,
): WaitingSettingsDraft {
  if (receptionMode !== 'PAUSED' && !draft.enabled) {
    return { ...draft, enabled: true, receptionMode }
  }
  return { ...draft, receptionMode }
}

export function setAdvanceOpenMinutes(
  draft: WaitingSettingsDraft,
  advanceOpenMinutes: number,
): WaitingSettingsDraft {
  return { ...draft, advanceOpenMinutes }
}

/** 서버 검증과 같은 규칙으로 미리 막는다. 통과해도 최종 판정은 서버가 한다. */
export function validateDraft(
  draft: WaitingSettingsDraft,
): Record<string, string> {
  const errors: Record<string, string> = {}

  if (!Number.isInteger(draft.advanceOpenMinutes)) {
    errors.advanceOpenMinutes = '분 단위 정수로 입력해 주세요.'
  } else if (
    draft.advanceOpenMinutes < ADVANCE_OPEN_MINUTES_MIN ||
    draft.advanceOpenMinutes > ADVANCE_OPEN_MINUTES_MAX
  ) {
    errors.advanceOpenMinutes = `${ADVANCE_OPEN_MINUTES_MIN}분 이상 ${ADVANCE_OPEN_MINUTES_MAX}분 이하로 입력해 주세요.`
  }

  if (!draft.enabled && draft.receptionMode !== 'PAUSED') {
    errors.receptionMode = '사용하지 않을 때는 신규 접수 중지만 선택할 수 있습니다.'
  }

  return errors
}

/** 초안이 조회한 설정과 같은지. 같으면 저장 요청을 보내지 않는다. */
export function isUnchanged(
  draft: WaitingSettingsDraft,
  setting: WaitingSetting,
): boolean {
  return (
    draft.enabled === setting.enabled &&
    draft.receptionMode === setting.receptionMode &&
    draft.advanceOpenMinutes === setting.advanceOpenMinutes
  )
}

/**
 * 저장 본문을 만든다.
 *
 * `disableAction`은 `enabled=false`인 요청에만 붙일 수 있다. 계약의 조건부
 * 스키마가 그렇게 정하며, 켜는 요청에 함께 보내면 검증에서 거절된다.
 */
export function toUpdateRequest(
  draft: WaitingSettingsDraft,
  expectedVersion: number,
  disableAction?: WaitingDisableAction,
): WaitingSettingUpdateRequest {
  const body: WaitingSettingUpdateRequest = {
    expectedVersion,
    enabled: draft.enabled,
    receptionMode: draft.receptionMode,
    advanceOpenMinutes: draft.advanceOpenMinutes,
  }
  if (!draft.enabled && disableAction !== undefined) {
    body.disableAction = disableAction
  }
  return body
}

/** 사용을 끄는 변경인지. 영향 확인이 필요한 시점을 이 판정이 정한다. */
export function isDisabling(
  draft: WaitingSettingsDraft,
  setting: WaitingSetting,
): boolean {
  return setting.enabled && !draft.enabled
}
