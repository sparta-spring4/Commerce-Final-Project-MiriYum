import { describe, expect, it } from 'vitest'
import {
  EMPTY_DRAFT,
  draftSignature,
  isScheduleComplete,
  partyTotal,
  readDraft,
  toCreateRequest,
  validateDraft,
  withMenuQuantity,
  withoutMenus,
  writeDraft,
  type ReservationDraft,
} from './draft'

const STORE_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1XA'
const MENU_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1MA'

function draft(overrides: Partial<ReservationDraft> = {}): ReservationDraft {
  return {
    ...EMPTY_DRAFT,
    serviceDate: '2026-09-01',
    startTime: '19:00',
    adultCount: '2',
    ...overrides,
  }
}

describe('readDraft', () => {
  it('매장 검색의 partySize를 성인 인원 초기값으로 쓴다', () => {
    const result = readDraft(new URLSearchParams('partySize=3'))

    expect(result.adultCount).toBe('3')
    // 아동·영유아 인원을 추측해 채우지 않는다.
    expect(result.childCount).toBe('0')
    expect(result.infantCount).toBe('0')
  })

  it('adultCount가 있으면 partySize보다 우선한다', () => {
    const result = readDraft(new URLSearchParams('partySize=3&adultCount=5'))

    expect(result.adultCount).toBe('5')
  })

  it('메뉴 선택을 menuId:quantity로 읽는다', () => {
    const result = readDraft(new URLSearchParams(`menu=${MENU_ID}:2`))

    expect(result.menuSelections.get(MENU_ID)).toBe(2)
  })

  it('수량이 범위를 벗어난 메뉴 항목은 버린다', () => {
    const result = readDraft(
      new URLSearchParams(`menu=${MENU_ID}:0&menu=${MENU_ID}x:abc`),
    )

    expect(result.menuSelections.size).toBe(0)
  })

  it('형식이 맞지 않는 날짜·시간은 미입력으로 되돌린다', () => {
    const result = readDraft(
      new URLSearchParams('serviceDate=2026-9-1&startTime=99:99'),
    )

    expect(result.serviceDate).toBe('')
    expect(result.startTime).toBe('')
  })
})

describe('writeDraft / readDraft 왕복', () => {
  it('메뉴 선택을 포함해 같은 draft로 복원된다', () => {
    const original = withMenuQuantity(draft(), MENU_ID, 2)
    const restored = readDraft(writeDraft(original))

    expect(restored.serviceDate).toBe('2026-09-01')
    expect(restored.startTime).toBe('19:00')
    expect(restored.adultCount).toBe('2')
    expect(restored.menuSelections.get(MENU_ID)).toBe(2)
  })
})

describe('partyTotal / isScheduleComplete', () => {
  it('세 인원의 합을 센다', () => {
    expect(
      partyTotal(draft({ adultCount: '2', childCount: '1', infantCount: '1' })),
    ).toBe(4)
  })

  it('날짜·시간·인원이 모두 있어야 완전하다', () => {
    expect(isScheduleComplete(draft())).toBe(true)
    expect(isScheduleComplete(draft({ startTime: '' }))).toBe(false)
    expect(
      isScheduleComplete(
        draft({ adultCount: '0', childCount: '0', infantCount: '0' }),
      ),
    ).toBe(false)
  })
})

describe('validateDraft', () => {
  it('완전한 draft는 오류가 없다', () => {
    expect(validateDraft(draft())).toEqual({})
  })

  it('인원이 0명이면 거절한다', () => {
    expect(
      validateDraft(
        draft({ adultCount: '0', childCount: '0', infantCount: '0' }),
      ).adultCount,
    ).toBe('방문 인원을 한 명 이상 입력해 주세요.')
  })

  it('날짜와 시간 오류를 각각 알린다', () => {
    const errors = validateDraft(draft({ serviceDate: '', startTime: '' }))

    expect(errors.serviceDate).toBeDefined()
    expect(errors.startTime).toBeDefined()
  })
})

describe('toCreateRequest', () => {
  it('계약이 정한 필드만 보낸다', () => {
    const body = toCreateRequest(STORE_ID, draft())

    // endTime·연락처·사용자 ID를 보내지 않는다.
    expect(Object.keys(body).sort()).toEqual([
      'menuSelections',
      'party',
      'serviceDate',
      'startTime',
      'storeId',
    ])
  })

  it('인원 구성을 그대로 담는다', () => {
    const body = toCreateRequest(
      STORE_ID,
      draft({ adultCount: '2', childCount: '1', infantCount: '1' }),
    )

    expect(body.party).toEqual({
      adultCount: 2,
      childCount: 1,
      infantCount: 1,
    })
  })

  it('메뉴 선택을 같은 요청에 함께 담는다', () => {
    const body = toCreateRequest(
      STORE_ID,
      withMenuQuantity(draft(), MENU_ID, 2),
    )

    // 예약 생성 뒤 홀드를 따로 호출하지 않는다.
    expect(body.menuSelections).toEqual([{ menuId: MENU_ID, quantity: 2 }])
  })

  it('메뉴가 없으면 빈 배열을 보낸다', () => {
    expect(toCreateRequest(STORE_ID, draft()).menuSelections).toEqual([])
  })
})

describe('withMenuQuantity / withoutMenus', () => {
  it('기존 draft를 변경하지 않는다', () => {
    const original = draft()
    const next = withMenuQuantity(original, MENU_ID, 1)

    expect(original.menuSelections.size).toBe(0)
    expect(next.menuSelections.get(MENU_ID)).toBe(1)
  })

  it('수량 0은 선택을 지운다', () => {
    const withMenu = withMenuQuantity(draft(), MENU_ID, 1)

    expect(withMenuQuantity(withMenu, MENU_ID, 0).menuSelections.size).toBe(0)
  })

  it('메뉴만 비운 draft를 만든다', () => {
    const withMenu = withMenuQuantity(draft(), MENU_ID, 1)
    const bare = withoutMenus(withMenu)

    expect(bare.menuSelections.size).toBe(0)
    expect(bare.serviceDate).toBe('2026-09-01')
  })
})

describe('draftSignature', () => {
  it('같은 입력은 같은 서명을 낸다', () => {
    expect(draftSignature(STORE_ID, draft())).toBe(
      draftSignature(STORE_ID, draft()),
    )
  })

  it('메뉴 순서가 달라도 같은 서명을 낸다', () => {
    const a = withMenuQuantity(withMenuQuantity(draft(), 'B', 1), 'A', 2)
    const b = withMenuQuantity(withMenuQuantity(draft(), 'A', 2), 'B', 1)

    expect(draftSignature(STORE_ID, a)).toBe(draftSignature(STORE_ID, b))
  })

  it('입력이 달라지면 서명도 달라진다', () => {
    expect(draftSignature(STORE_ID, draft())).not.toBe(
      draftSignature(STORE_ID, draft({ adultCount: '3' })),
    )
  })
})
