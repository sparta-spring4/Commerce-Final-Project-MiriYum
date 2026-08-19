/**
 * 매장·대표자 입력의 제출 전 검증.
 *
 * 규칙은 auth-account·store-search OpenAPI 스키마 제약을 그대로 옮긴 것이다.
 * 서버가 같은 규칙을 다시 검증하므로 여기 통과를 성공으로 표시하지 않는다.
 */

/** `businessRegistrationNumber`: pattern `^[0-9]{10}$` */
const BUSINESS_NUMBER_PATTERN = /^[0-9]{10}$/
const STORE_NAME_MAX_LENGTH = 100
const DESCRIPTION_MAX_LENGTH = 1000
const ADDRESS_MAX_LENGTH = 300
export const MAX_TAG_CODES = 20

/** 입력 편의를 위해 하이픈·공백을 지운다. 제출값은 숫자 10자리다. */
export function normalizeBusinessNumber(value: string): string {
  return value.replace(/[\s-]/g, '')
}

export function validateBusinessNumber(value: string): string | null {
  if (value.length === 0) {
    return '사업자등록번호를 입력해 주세요.'
  }
  if (!BUSINESS_NUMBER_PATTERN.test(normalizeBusinessNumber(value))) {
    return '사업자등록번호는 숫자 10자리입니다.'
  }
  return null
}

export function validateStoreName(value: string): string | null {
  if (value.trim().length === 0) {
    return '매장명을 입력해 주세요.'
  }
  if (value.length > STORE_NAME_MAX_LENGTH) {
    return `매장명은 ${STORE_NAME_MAX_LENGTH}자를 넘을 수 없습니다.`
  }
  return null
}

export function validateStoreDescription(value: string): string | null {
  if (value.length > DESCRIPTION_MAX_LENGTH) {
    return `매장 소개는 ${DESCRIPTION_MAX_LENGTH}자를 넘을 수 없습니다.`
  }
  return null
}

export function validateStoreAddress(value: string): string | null {
  if (value.trim().length === 0) {
    return '매장 주소를 입력해 주세요.'
  }
  if (value.length > ADDRESS_MAX_LENGTH) {
    return `주소는 ${ADDRESS_MAX_LENGTH}자를 넘을 수 없습니다.`
  }
  return null
}

export function validateCatalogSelection(code: string): string | null {
  return code.length === 0 ? '카테고리를 선택해 주세요.' : null
}

export function validateTagCodes(codes: readonly string[]): string | null {
  if (codes.length > MAX_TAG_CODES) {
    return `태그는 최대 ${MAX_TAG_CODES}개까지 선택할 수 있습니다.`
  }
  if (new Set(codes).size !== codes.length) {
    return '같은 태그를 두 번 선택할 수 없습니다.'
  }
  return null
}

/** 게시·게시 취소는 변경 사유가 필수다. 화면이 빈 값으로 보내지 않게 막는다. */
export function validateChangeReason(value: string): string | null {
  return value.trim().length === 0 ? '변경 사유를 입력해 주세요.' : null
}
