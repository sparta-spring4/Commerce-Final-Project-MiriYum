import { describe, expect, it } from 'vitest'
import { validateMenuImage } from './menuImage'

describe('메뉴 대표 이미지 검증', () => {
  it.each([
    ['menu.jpg', 'image/jpeg'],
    ['menu.png', 'image/png'],
    ['menu.webp', 'image/webp'],
  ])('%s 형식을 허용한다', (name, type) => {
    expect(validateMenuImage(new File(['image'], name, { type }))).toBeNull()
  })

  it('지원하지 않는 이미지 형식을 거절한다', () => {
    expect(
      validateMenuImage(new File(['gif'], 'menu.gif', { type: 'image/gif' })),
    ).toBe('JPG, PNG, WEBP 이미지 파일만 등록할 수 있습니다.')
  })

  it('10MiB를 넘는 파일을 거절한다', () => {
    const file = new File(['small'], 'menu.png', { type: 'image/png' })
    Object.defineProperty(file, 'size', { value: 10 * 1024 * 1024 + 1 })

    expect(validateMenuImage(file)).toBe(
      '이미지 파일은 10MB 이하만 등록할 수 있습니다.',
    )
  })
})
