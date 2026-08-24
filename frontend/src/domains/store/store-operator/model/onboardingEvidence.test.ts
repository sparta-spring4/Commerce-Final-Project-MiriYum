import { describe, expect, it } from 'vitest'
import { validateOnboardingEvidence } from './onboardingEvidence'

describe('사업자등록증 제출 파일 검증', () => {
  it('파일을 반드시 요구한다', () => {
    expect(validateOnboardingEvidence(undefined)).toBe(
      '사업자등록증 파일을 선택해 주세요.',
    )
  })

  it.each([
    ['certificate.pdf', 'application/pdf'],
    ['certificate.jpg', 'image/jpeg'],
    ['certificate.png', 'image/png'],
  ])('%s 형식을 허용한다', (name, type) => {
    expect(validateOnboardingEvidence(new File(['valid'], name, { type }))).toBeNull()
  })

  it('계약에 없는 이미지 형식을 거절한다', () => {
    expect(
      validateOnboardingEvidence(
        new File(['gif'], 'certificate.gif', { type: 'image/gif' }),
      ),
    ).toBe('PDF, JPG, PNG 파일만 제출할 수 있습니다.')
  })

  it('10MiB를 넘는 파일을 거절한다', () => {
    const file = new File(['small'], 'certificate.png', { type: 'image/png' })
    Object.defineProperty(file, 'size', { value: 10 * 1024 * 1024 + 1 })

    expect(validateOnboardingEvidence(file)).toBe(
      '사업자등록증 파일은 10MB 이하만 제출할 수 있습니다.',
    )
  })
})
