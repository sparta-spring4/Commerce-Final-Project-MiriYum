const MAX_EVIDENCE_BYTES = 10 * 1024 * 1024
const ACCEPTED_EVIDENCE_TYPES = new Set([
  'application/pdf',
  'image/jpeg',
  'image/png',
])

export const ONBOARDING_EVIDENCE_ACCEPT =
  'application/pdf,image/jpeg,image/png'

/** 서버가 signature를 확인하기 전에 선택 단계에서 확인 가능한 계약만 검사한다. */
export function validateOnboardingEvidence(
  file: File | undefined,
): string | null {
  if (file === undefined) {
    return '사업자등록증 파일을 선택해 주세요.'
  }
  if (!ACCEPTED_EVIDENCE_TYPES.has(file.type)) {
    return 'PDF, JPG, PNG 파일만 제출할 수 있습니다.'
  }
  if (file.size > MAX_EVIDENCE_BYTES) {
    return '사업자등록증 파일은 10MB 이하만 제출할 수 있습니다.'
  }
  return null
}
