const DISPLAY_NAME_MIN_LENGTH = 2
const DISPLAY_NAME_MAX_LENGTH = 50

export function validateDisplayName(value: string): string | null {
  if (value.length === 0) return '대표자명을 입력해 주세요.'
  if (value.length < DISPLAY_NAME_MIN_LENGTH || value.length > DISPLAY_NAME_MAX_LENGTH) {
    return `대표자명은 ${DISPLAY_NAME_MIN_LENGTH}~${DISPLAY_NAME_MAX_LENGTH}자여야 합니다.`
  }
  return null
}
