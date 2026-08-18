import { describe, expect, it } from 'vitest'
import {
  createMenuDraftForm,
  menuFormFromVersion,
  toMenuWriteRequest,
  validateMenuDraft,
  type MenuDraftForm,
} from './menuDraft'
import { menuVersion } from '../test/handlers'

function validForm(overrides: Partial<MenuDraftForm> = {}): MenuDraftForm {
  return {
    ...createMenuDraftForm(),
    name: '에스프레소',
    price: '4500',
    primaryCategoryCode: 'COFFEE',
    allergenInformationStatus: 'NOT_REGISTERED',
    originInformationStatus: 'NOT_APPLICABLE',
    ...overrides,
  }
}

describe('메뉴 초안 검증', () => {
  it('표시 정보 상태를 고르지 않으면 저장하지 않는다', () => {
    const errors = validateMenuDraft(
      validForm({
        allergenInformationStatus: '',
        originInformationStatus: '',
      }),
    )

    // 빈 배열이나 미등록을 "안전"으로 추론하지 않고 명시적 선택을 요구한다.
    expect(errors.allergenInformationStatus).toBe(
      '알레르기 표시 여부를 선택해 주세요.',
    )
    expect(errors.originInformationStatus).toBe(
      '원산지 표시 여부를 선택해 주세요.',
    )
  })

  it('알레르기에는 해당 없음을 쓸 수 없다', () => {
    const errors = validateMenuDraft(
      validForm({ allergenInformationStatus: 'NOT_APPLICABLE' }),
    )

    expect(errors.allergenInformationStatus).toBe(
      '알레르기에는 "해당 없음"을 사용할 수 없습니다.',
    )
  })

  it('알레르기를 등록으로 두면 항목이 최소 한 개 필요하다', () => {
    const errors = validateMenuDraft(
      validForm({
        allergenInformationStatus: 'REGISTERED',
        allergenDisclosures: [],
      }),
    )

    expect(errors.allergenDisclosures).toBe(
      '등록으로 두려면 항목을 최소 한 개 추가해 주세요.',
    )
  })

  it('같은 원재료를 두 번 담을 수 없다', () => {
    const errors = validateMenuDraft(
      validForm({
        allergenInformationStatus: 'REGISTERED',
        allergenDisclosures: [
          { ingredientCode: 'MILK', status: 'CONTAINS' },
          { ingredientCode: 'MILK', status: 'MAY_CONTAIN' },
        ],
      }),
    )

    expect(errors.allergenDisclosures).toBe(
      '같은 원재료를 두 번 추가할 수 없습니다.',
    )
  })

  it('원산지를 등록으로 두면 원재료와 원산지를 모두 입력해야 한다', () => {
    const errors = validateMenuDraft(
      validForm({
        originInformationStatus: 'REGISTERED',
        originDisclosures: [{ ingredient: '원두', origin: '' }],
      }),
    )

    expect(errors.originDisclosures).toBe('원재료와 원산지를 모두 입력해 주세요.')
  })

  it('가격은 정수여야 한다', () => {
    expect(validateMenuDraft(validForm({ price: '4500.5' })).price).toBe(
      '가격은 정수로 입력해 주세요.',
    )
  })

  it('주 카테고리를 보조 카테고리로 다시 고를 수 없다', () => {
    const errors = validateMenuDraft(
      validForm({ secondaryCategoryCodes: ['COFFEE'] }),
    )

    expect(errors.secondaryCategoryCodes).toBe(
      '주 카테고리와 같은 항목은 보조로 선택할 수 없습니다.',
    )
  })

  it('검증을 통과하면 계약 본문으로 옮긴다', () => {
    const body = toMenuWriteRequest(
      validForm({ name: '  에스프레소  ', alcoholic: true }),
    )

    expect(body).toEqual({
      name: '에스프레소',
      description: '',
      price: 4500,
      representative: false,
      primaryCategoryCode: 'COFFEE',
      secondaryCategoryCodes: [],
      localTags: [],
      holdSelectionAllowed: false,
      pickupSelectionAllowed: false,
      allergenInformationStatus: 'NOT_REGISTERED',
      allergenDisclosures: [],
      originInformationStatus: 'NOT_APPLICABLE',
      originDisclosures: [],
      alcoholic: true,
    })
  })

  it('상태가 미선택이면 본문을 만들지 않는다', () => {
    expect(
      toMenuWriteRequest(validForm({ allergenInformationStatus: '' })),
    ).toBeNull()
  })

  it('서버가 준 버전으로 폼을 채운다', () => {
    const form = menuFormFromVersion(
      menuVersion({ price: 6000, representative: true }),
    )

    expect(form.price).toBe('6000')
    expect(form.representative).toBe(true)
    expect(validateMenuDraft(form)).toEqual({})
  })
})
