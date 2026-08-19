/**
 * 가입·로그인 폼의 제출 전 검증.
 *
 * 서버가 같은 규칙을 다시 검증한다. 여기서 통과했다고 성공으로 표시하지 않고,
 * 서버 응답 후에도 필드 오류를 다시 반영한다.
 * 규칙은 auth-account OpenAPI의 스키마 제약을 그대로 옮긴 것이다.
 */

/** `Email`: format email, maxLength 254 */
const EMAIL_MAX_LENGTH = 254
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** `Password`: 8~64 code point, 4종 중 3종 이상 */
const PASSWORD_MIN_LENGTH = 8
const PASSWORD_MAX_LENGTH = 64
const REQUIRED_CHARACTER_CLASSES = 3

/** `nickname`: 2~20자, 한글·영문·숫자·공백·언더스코어·하이픈 */
const NICKNAME_PATTERN = /^[가-힣A-Za-z0-9 _-]+$/
const NICKNAME_MIN_LENGTH = 2
const NICKNAME_MAX_LENGTH = 20

/** `MvpPhoneNumber`: 정규화 후 010으로 시작하는 11자리 */
const NORMALIZED_PHONE_PATTERN = /^010[0-9]{8}$/

export function validateEmail(value: string): string | null {
  if (value.length === 0) {
    return '이메일을 입력해 주세요.'
  }
  if (value.length > EMAIL_MAX_LENGTH) {
    return `이메일은 ${EMAIL_MAX_LENGTH}자를 넘을 수 없습니다.`
  }
  if (!EMAIL_PATTERN.test(value)) {
    return '이메일 형식으로 입력해 주세요.'
  }
  return null
}

/**
 * 비밀번호 문자 종류를 센다.
 *
 * 계약은 대문자·소문자·숫자·특수문자 가운데 3종 이상을 요구한다.
 * 정규화(NFC)는 서버가 하고, 여기서는 사용자가 입력한 그대로 세어 안내만 한다.
 */
function countCharacterClasses(value: string): number {
  const classes = [
    /[A-Z]/u,
    /[a-z]/u,
    /[0-9]/u,
    /[^A-Za-z0-9]/u,
  ]
  return classes.filter((pattern) => pattern.test(value)).length
}

export function validatePassword(value: string): string | null {
  if (value.length === 0) {
    return '비밀번호를 입력해 주세요.'
  }
  // code point 기준이므로 문자열 length가 아니라 실제 글자 수로 센다.
  const codePoints = [...value].length
  if (codePoints < PASSWORD_MIN_LENGTH || codePoints > PASSWORD_MAX_LENGTH) {
    return `비밀번호는 ${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH}자여야 합니다.`
  }
  if (countCharacterClasses(value) < REQUIRED_CHARACTER_CLASSES) {
    return '대문자·소문자·숫자·특수문자 가운데 3종 이상을 포함해 주세요.'
  }
  return null
}

export function validatePasswordConfirm(
  password: string,
  confirm: string,
): string | null {
  if (confirm.length === 0) {
    return '비밀번호를 한 번 더 입력해 주세요.'
  }
  if (password !== confirm) {
    return '비밀번호가 서로 다릅니다.'
  }
  return null
}

export function validateNickname(value: string): string | null {
  if (value.length === 0) {
    return '닉네임을 입력해 주세요.'
  }
  if (
    value.length < NICKNAME_MIN_LENGTH ||
    value.length > NICKNAME_MAX_LENGTH
  ) {
    return `닉네임은 ${NICKNAME_MIN_LENGTH}~${NICKNAME_MAX_LENGTH}자여야 합니다.`
  }
  if (!NICKNAME_PATTERN.test(value)) {
    return '닉네임은 한글·영문·숫자와 공백, _, - 만 사용할 수 있습니다.'
  }
  return null
}

/**
 * 입력 원문에서 공백과 하이픈을 제거한다.
 *
 * 계약은 서버가 정규화한다고 정하지만, 정규화 결과가 형식에 맞는지 미리
 * 확인해야 사용자가 제출 전에 오타를 고칠 수 있다.
 */
export function normalizePhoneNumber(value: string): string {
  return value.replace(/[\s-]/g, '')
}

export function validatePhoneNumber(value: string): string | null {
  if (value.length === 0) {
    return '휴대전화 번호를 입력해 주세요.'
  }
  if (!NORMALIZED_PHONE_PATTERN.test(normalizePhoneNumber(value))) {
    return '010으로 시작하는 11자리 번호를 입력해 주세요.'
  }
  return null
}

export function validateAgeConfirmed(value: boolean): string | null {
  return value ? null : '만 14세 이상임을 확인해 주세요.'
}

/** 필드 이름 → 오류 문구. 값이 없는 필드는 오류가 없다는 뜻이다. */
export type FieldErrors = Readonly<Record<string, string>>

/** null이 아닌 항목만 모아 오류 맵을 만든다. */
export function collectErrors(
  entries: Readonly<Record<string, string | null>>,
): FieldErrors {
  const errors: Record<string, string> = {}
  for (const [field, message] of Object.entries(entries)) {
    if (message !== null) {
      errors[field] = message
    }
  }
  return errors
}
