import type { MenuInventoryCreateRequest, MenuInventoryUpdateRequest } from './types'

export interface InventoryDraft {
  menuId: string
  serviceDate: string
  startTime: string
  endDate: string
  endTime: string
  totalSupply: string
  onlineHold: string
  onsite: string
  shared: string
  sharedOnlineAllowed: boolean
  availabilityStatus: 'AVAILABLE' | 'SOLD_OUT'
}

export function validateInventoryDraft(draft: InventoryDraft, creating: boolean): string | null {
  if (creating && (!draft.menuId || !draft.serviceDate || !draft.startTime || !draft.endDate || !draft.endTime)) {
    return '메뉴와 제공 시작·종료 일시를 모두 입력해 주세요.'
  }
  const values = [draft.totalSupply, draft.onlineHold, draft.onsite, draft.shared].map(Number)
  if (values.some((value) => !Number.isInteger(value) || value < 0)) {
    return '수량은 0 이상의 정수로 입력해 주세요.'
  }
  if (values[1] + values[2] + values[3] !== values[0]) {
    return '세 풀의 합계가 총 공급과 같아야 합니다.'
  }
  return null
}

function seconds(time: string): string {
  return time.length === 5 ? `${time}:00` : time
}

export function toInventoryUpdate(draft: InventoryDraft): MenuInventoryUpdateRequest {
  return {
    totalSupply: Number(draft.totalSupply),
    pools: { onlineHold: Number(draft.onlineHold), onsite: Number(draft.onsite), shared: Number(draft.shared) },
    sharedOnlineAllowed: draft.sharedOnlineAllowed,
    availabilityStatus: draft.availabilityStatus,
  }
}

export function toInventoryCreate(draft: InventoryDraft): MenuInventoryCreateRequest {
  return {
    menuId: draft.menuId,
    serviceDate: draft.serviceDate,
    startTime: seconds(draft.startTime),
    endDate: draft.endDate,
    endTime: seconds(draft.endTime),
    ...toInventoryUpdate(draft),
  }
}
