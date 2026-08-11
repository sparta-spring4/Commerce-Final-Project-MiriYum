import { afterEach, describe, expect, it, vi } from 'vitest'

import { KakaoMapLoader } from './KakaoMapLoader'

afterEach(() => {
  document.head.querySelectorAll('script[data-miriyum-kakao-map]').forEach((script) => {
    script.remove()
  })
  delete window.kakao
})

describe('KakaoMapLoader', () => {
  it('shares one SDK script and one completion promise across concurrent calls', async () => {
    const loader = new KakaoMapLoader()
    const first = loader.load('javascript-key')
    const second = loader.load('javascript-key')

    expect(second).toBe(first)

    const scripts = document.head.querySelectorAll(
      'script[data-miriyum-kakao-map]',
    )
    expect(scripts).toHaveLength(1)
    expect(scripts[0]?.getAttribute('src')).toBe(
      'https://dapi.kakao.com/v2/maps/sdk.js?appkey=javascript-key&autoload=false',
    )

    const maps = { load: vi.fn((callback: () => void) => callback()) }
    window.kakao = {
      maps: maps as unknown as KakaoMapsNamespace,
    }
    scripts[0]?.dispatchEvent(new Event('load'))

    await expect(first).resolves.toBe(maps)
    expect(maps.load).toHaveBeenCalledOnce()
  })

  it('rejects a missing JavaScript key without adding a script', async () => {
    const loader = new KakaoMapLoader()

    await expect(loader.load('   ')).rejects.toThrow(
      'Kakao 지도 JavaScript 키가 설정되지 않았습니다.',
    )
    expect(
      document.head.querySelectorAll('script[data-miriyum-kakao-map]'),
    ).toHaveLength(0)
  })

  it('shares the same failure when the SDK script cannot load', async () => {
    const loader = new KakaoMapLoader()
    const first = loader.load('javascript-key')
    const second = loader.load('javascript-key')
    const script = document.head.querySelector<HTMLScriptElement>(
      'script[data-miriyum-kakao-map]',
    )

    script?.dispatchEvent(new Event('error'))

    await expect(first).rejects.toThrow('Kakao 지도 SDK를 불러오지 못했습니다.')
    await expect(second).rejects.toThrow('Kakao 지도 SDK를 불러오지 못했습니다.')
  })
})
