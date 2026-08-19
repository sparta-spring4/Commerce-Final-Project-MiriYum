import { describe, expect, it } from 'vitest'
import { ApiError } from '../../../../shared/api/apiError'
import {
  EMPTY_PICKUP_DRAFT,
  PickupErrorCode,
  pickupTotalPrice,
  readPickupDraft,
  toPickupCancelMessage,
  toPickupCreateMessage,
  toPickupCreateRequest,
  validatePickupDraft,
  withPickupQuantity,
  withPickupSlot,
  writePickupDraft,
  type PickupDraft,
} from './pickup'

const STORE_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1XA'
const MENU_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1MA'

function draft(overrides: Partial<PickupDraft> = {}): PickupDraft {
  return {
    ...EMPTY_PICKUP_DRAFT,
    pickupDate: '2026-09-01',
    pickupTime: '18:30',
    menuSelections: new Map([[MENU_ID, 2]]),
    ...overrides,
  }
}

function apiError(code: string): ApiError {
  return new ApiError({ status: 409, code, message: '충돌' })
}

describe('readPickupDraft', () => {
  it('매장 상세에서 넘어온 serviceDate·startTime을 초기값으로 받는다', () => {
    const result = readPickupDraft(
      new URLSearchParams('serviceDate=2026-09-01&startTime=18:30'),
    )

    expect(result.pickupDate).toBe('2026-09-01')
    expect(result.pickupTime).toBe('18:30')
  })

  it('pickupDate가 있으면 serviceDate보다 우선한다', () => {
    const result = readPickupDraft(
      new URLSearchParams('serviceDate=2026-09-01&pickupDate=2026-10-01'),
    )

    expect(result.pickupDate).toBe('2026-10-01')
  })

  it('왕복해도 같은 draft가 된다', () => {
    const restored = readPickupDraft(writePickupDraft(draft()))

    expect(restored.pickupDate).toBe('2026-09-01')
    expect(restored.pickupTime).toBe('18:30')
    expect(restored.menuSelections.get(MENU_ID)).toBe(2)
  })
})

describe('withPickupSlot', () => {
  it('시간대를 바꾸면 메뉴 선택을 비운다', () => {
    // 재고 버킷이 구간마다 다르므로 이전 구간의 수량을 들고 가지 않는다.
    const next = withPickupSlot(draft(), '19:00')

    expect(next.pickupTime).toBe('19:00')
    expect(next.menuSelections.size).toBe(0)
  })

  it('같은 시간대를 다시 고르면 선택을 유지한다', () => {
    const next = withPickupSlot(draft(), '18:30')

    expect(next.menuSelections.get(MENU_ID)).toBe(2)
  })
})

describe('withPickupQuantity', () => {
  it('기존 draft를 변경하지 않는다', () => {
    const original = draft()
    const next = withPickupQuantity(original, MENU_ID, 3)

    expect(original.menuSelections.get(MENU_ID)).toBe(2)
    expect(next.menuSelections.get(MENU_ID)).toBe(3)
  })

  it('수량 0은 선택을 지운다', () => {
    expect(
      withPickupQuantity(draft(), MENU_ID, 0).menuSelections.size,
    ).toBe(0)
  })
})

describe('validatePickupDraft', () => {
  it('완전한 draft는 오류가 없다', () => {
    expect(validatePickupDraft(draft())).toEqual({})
  })

  it('메뉴를 하나도 고르지 않으면 거절한다', () => {
    expect(
      validatePickupDraft(draft({ menuSelections: new Map() })).menuSelections,
    ).toBe('픽업할 메뉴를 한 가지 이상 선택해 주세요.')
  })

  it('날짜와 시간대 오류를 각각 알린다', () => {
    const errors = validatePickupDraft(
      draft({ pickupDate: '', pickupTime: '' }),
    )

    expect(errors.pickupDate).toBeDefined()
    expect(errors.pickupTime).toBeDefined()
  })
})

describe('toPickupCreateRequest', () => {
  it('계약이 정한 네 필드만 보낸다', () => {
    const body = toPickupCreateRequest(STORE_ID, draft())

    // endTime·partySize를 보내지 않는다.
    expect(Object.keys(body).sort()).toEqual([
      'menuSelections',
      'pickupDate',
      'pickupTime',
      'storeId',
    ])
  })

  it('메뉴 선택을 배열로 옮긴다', () => {
    expect(toPickupCreateRequest(STORE_ID, draft()).menuSelections).toEqual([
      { menuId: MENU_ID, quantity: 2 },
    ])
  })
})

describe('pickupTotalPrice', () => {
  it('단가와 수량을 곱해 합산한다', () => {
    expect(
      pickupTotalPrice({
        items: [
          { menuId: 'a', menuName: 'A', unitPrice: 1000, quantity: 2 },
          { menuId: 'b', menuName: 'B', unitPrice: 500, quantity: 3 },
        ],
      }),
    ).toBe(3500)
  })
})

describe('오류 문구', () => {
  it('생성 오류를 서버 code로 분기한다', () => {
    expect(toPickupCreateMessage(apiError(PickupErrorCode.NOT_ELIGIBLE))).toBe(
      '이 매장은 지금 픽업 예약을 받지 않습니다.',
    )
    expect(toPickupCreateMessage(apiError(PickupErrorCode.SLOT_INVALID))).toContain(
      '픽업 시간대',
    )
    expect(
      toPickupCreateMessage(apiError(PickupErrorCode.INSUFFICIENT_QUANTITY)),
    ).toContain('수량이 부족')
  })

  it('취소 오류는 전이 불가와 정책 불가로만 나눈다', () => {
    expect(toPickupCancelMessage(apiError(PickupErrorCode.INVALID_STATE))).toContain(
      '현재 상태에서는 취소할 수 없습니다',
    )
    expect(
      toPickupCancelMessage(apiError(PickupErrorCode.CANCELLATION_NOT_ALLOWED)),
    ).toContain('취소 정책상')
    // 생성 오류 코드가 취소 문구로 새지 않는다.
    expect(
      toPickupCancelMessage(apiError(PickupErrorCode.INSUFFICIENT_QUANTITY)),
    ).toBe('픽업 예약을 취소하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  })
})
