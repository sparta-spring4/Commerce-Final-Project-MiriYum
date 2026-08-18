import type {
  AllergenDisclosure,
  DisclosureRegistrationStatus,
  MenuVersion,
  MenuWriteRequest,
  OriginDisclosure,
} from './types'

/**
 * 메뉴 내용 초안 폼 모델.
 *
 * 알레르기·원산지·주류 여부는 계약이 매 요청에 전부 요구한다. 빈 배열이나
 * `NOT_REGISTERED`를 "안전"이나 "해당 없음"으로 추론하지 않고 운영자에게 명시적으로
 * 입력받는다. 그래서 초기값을 미선택(`''`)으로 두고 선택을 강제한다.
 */

const NAME_MAX_LENGTH = 100
const DESCRIPTION_MAX_LENGTH = 1000
const PRICE_MAX = 2_000_000_000
const MAX_SECONDARY_CATEGORIES = 5
const MAX_LOCAL_TAGS = 10
const LOCAL_TAG_MAX_LENGTH = 30
const MAX_DISCLOSURES = 20
const INGREDIENT_MAX_LENGTH = 100
const ORIGIN_MAX_LENGTH = 200

/** 미선택을 표현할 수 있는 공개 상태. 폼에서만 쓰고 요청에는 실리지 않는다. */
export type DisclosureStatusInput = DisclosureRegistrationStatus | ''

export interface MenuDraftForm {
  name: string
  description: string
  /** 숫자 입력을 문자열로 들고 있다가 제출 시점에 정수로 바꾼다. */
  price: string
  representative: boolean
  primaryCategoryCode: string
  secondaryCategoryCodes: string[]
  localTags: string[]
  holdSelectionAllowed: boolean
  pickupSelectionAllowed: boolean
  allergenInformationStatus: DisclosureStatusInput
  allergenDisclosures: AllergenDisclosure[]
  originInformationStatus: DisclosureStatusInput
  originDisclosures: OriginDisclosure[]
  alcoholic: boolean
}

export function createMenuDraftForm(): MenuDraftForm {
  return {
    name: '',
    description: '',
    price: '',
    representative: false,
    primaryCategoryCode: '',
    secondaryCategoryCodes: [],
    localTags: [],
    holdSelectionAllowed: false,
    pickupSelectionAllowed: false,
    allergenInformationStatus: '',
    allergenDisclosures: [],
    originInformationStatus: '',
    originDisclosures: [],
    alcoholic: false,
  }
}

/**
 * 서버가 준 버전으로 폼을 채운다.
 *
 * 초안이 있으면 초안을, 없으면 게시된 버전을 기준으로 편집을 시작한다.
 * 게시 스냅샷 자체를 바꾸는 것이 아니라 새 초안의 출발점으로만 쓴다.
 */
export function menuFormFromVersion(version: MenuVersion): MenuDraftForm {
  return {
    name: version.name,
    description: version.description,
    price: String(version.price),
    representative: version.representative,
    primaryCategoryCode: version.primaryCategoryCode,
    secondaryCategoryCodes: [...version.secondaryCategoryCodes],
    localTags: [...version.localTags],
    holdSelectionAllowed: version.holdSelectionAllowed,
    pickupSelectionAllowed: version.pickupSelectionAllowed,
    allergenInformationStatus: version.allergenInformationStatus,
    allergenDisclosures: version.allergenDisclosures.map((entry) => ({
      ...entry,
    })),
    originInformationStatus: version.originInformationStatus,
    originDisclosures: version.originDisclosures.map((entry) => ({ ...entry })),
    alcoholic: version.alcoholic,
  }
}

function validatePrice(value: string): string | null {
  if (value.trim().length === 0) {
    return '가격을 입력해 주세요.'
  }
  const price = Number(value)
  if (!Number.isInteger(price)) {
    return '가격은 정수로 입력해 주세요.'
  }
  if (price < 0 || price > PRICE_MAX) {
    return `가격은 0 이상 ${PRICE_MAX.toLocaleString('ko-KR')} 이하여야 합니다.`
  }
  return null
}

function validateAllergenSection(
  form: MenuDraftForm,
): Readonly<Record<string, string>> {
  const errors: Record<string, string> = {}

  if (form.allergenInformationStatus === '') {
    errors.allergenInformationStatus = '알레르기 표시 여부를 선택해 주세요.'
    return errors
  }
  // NOT_APPLICABLE은 원산지 전용이다. 알레르기에 쓰면 서버가 거절한다.
  if (form.allergenInformationStatus === 'NOT_APPLICABLE') {
    errors.allergenInformationStatus =
      '알레르기에는 "해당 없음"을 사용할 수 없습니다.'
    return errors
  }
  if (
    form.allergenInformationStatus === 'REGISTERED' &&
    form.allergenDisclosures.length === 0
  ) {
    errors.allergenDisclosures = '등록으로 두려면 항목을 최소 한 개 추가해 주세요.'
  }
  if (form.allergenDisclosures.length > MAX_DISCLOSURES) {
    errors.allergenDisclosures = `알레르기 항목은 최대 ${MAX_DISCLOSURES}개입니다.`
  }
  const codes = form.allergenDisclosures.map((entry) => entry.ingredientCode)
  if (new Set(codes).size !== codes.length) {
    errors.allergenDisclosures = '같은 원재료를 두 번 추가할 수 없습니다.'
  }
  return errors
}

function validateOriginSection(
  form: MenuDraftForm,
): Readonly<Record<string, string>> {
  const errors: Record<string, string> = {}

  if (form.originInformationStatus === '') {
    errors.originInformationStatus = '원산지 표시 여부를 선택해 주세요.'
    return errors
  }
  if (
    form.originInformationStatus === 'REGISTERED' &&
    form.originDisclosures.length === 0
  ) {
    errors.originDisclosures = '등록으로 두려면 항목을 최소 한 개 추가해 주세요.'
  }
  if (form.originDisclosures.length > MAX_DISCLOSURES) {
    errors.originDisclosures = `원산지 항목은 최대 ${MAX_DISCLOSURES}개입니다.`
  }
  const invalid = form.originDisclosures.some(
    (entry) =>
      entry.ingredient.trim().length === 0 ||
      entry.origin.trim().length === 0 ||
      entry.ingredient.length > INGREDIENT_MAX_LENGTH ||
      entry.origin.length > ORIGIN_MAX_LENGTH,
  )
  if (invalid) {
    errors.originDisclosures = '원재료와 원산지를 모두 입력해 주세요.'
  }
  return errors
}

export function validateMenuDraft(
  form: MenuDraftForm,
): Readonly<Record<string, string>> {
  const errors: Record<string, string> = {}

  if (form.name.trim().length === 0) {
    errors.name = '메뉴명을 입력해 주세요.'
  } else if (form.name.length > NAME_MAX_LENGTH) {
    errors.name = `메뉴명은 ${NAME_MAX_LENGTH}자를 넘을 수 없습니다.`
  }
  if (form.description.length > DESCRIPTION_MAX_LENGTH) {
    errors.description = `메뉴 소개는 ${DESCRIPTION_MAX_LENGTH}자를 넘을 수 없습니다.`
  }
  const priceError = validatePrice(form.price)
  if (priceError !== null) {
    errors.price = priceError
  }
  if (form.primaryCategoryCode.length === 0) {
    errors.primaryCategoryCode = '주 카테고리를 선택해 주세요.'
  }
  if (form.secondaryCategoryCodes.length > MAX_SECONDARY_CATEGORIES) {
    errors.secondaryCategoryCodes = `보조 카테고리는 최대 ${MAX_SECONDARY_CATEGORIES}개입니다.`
  }
  if (form.secondaryCategoryCodes.includes(form.primaryCategoryCode)) {
    errors.secondaryCategoryCodes =
      '주 카테고리와 같은 항목은 보조로 선택할 수 없습니다.'
  }
  if (form.localTags.length > MAX_LOCAL_TAGS) {
    errors.localTags = `자유 태그는 최대 ${MAX_LOCAL_TAGS}개입니다.`
  }
  if (form.localTags.some((tag) => tag.length > LOCAL_TAG_MAX_LENGTH)) {
    errors.localTags = `태그는 ${LOCAL_TAG_MAX_LENGTH}자를 넘을 수 없습니다.`
  }

  return {
    ...errors,
    ...validateAllergenSection(form),
    ...validateOriginSection(form),
  }
}

/**
 * 검증을 통과한 폼을 계약 본문으로 옮긴다.
 *
 * 상태가 미선택인 폼은 여기 도달하지 않는다. 도달했다면 검증을 건너뛴 것이므로
 * 값을 임의로 채우지 않고 null을 돌려 호출자가 멈추게 한다.
 */
export function toMenuWriteRequest(
  form: MenuDraftForm,
): MenuWriteRequest | null {
  if (
    form.allergenInformationStatus === '' ||
    form.originInformationStatus === ''
  ) {
    return null
  }
  return {
    name: form.name.trim(),
    description: form.description,
    price: Number(form.price),
    representative: form.representative,
    primaryCategoryCode: form.primaryCategoryCode,
    secondaryCategoryCodes: form.secondaryCategoryCodes,
    localTags: form.localTags,
    holdSelectionAllowed: form.holdSelectionAllowed,
    pickupSelectionAllowed: form.pickupSelectionAllowed,
    allergenInformationStatus: form.allergenInformationStatus,
    allergenDisclosures: form.allergenDisclosures,
    originInformationStatus: form.originInformationStatus,
    originDisclosures: form.originDisclosures.map((entry) => ({
      ingredient: entry.ingredient.trim(),
      origin: entry.origin.trim(),
    })),
    alcoholic: form.alcoholic,
  }
}
