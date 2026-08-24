const MAX_MENU_IMAGE_BYTES = 10 * 1024 * 1024
const ACCEPTED_MENU_IMAGE_TYPES = new Set([
  'image/jpeg',
  'image/png',
  'image/webp',
])

export const MENU_IMAGE_ACCEPT = 'image/jpeg,image/png,image/webp'

export function validateMenuImage(file: File): string | null {
  if (!ACCEPTED_MENU_IMAGE_TYPES.has(file.type)) {
    return 'JPG, PNG, WEBP 이미지 파일만 등록할 수 있습니다.'
  }
  if (file.size > MAX_MENU_IMAGE_BYTES) {
    return '이미지 파일은 10MB 이하만 등록할 수 있습니다.'
  }
  return null
}
