import { afterEach, describe, expect, it, vi } from 'vitest'

import { KakaoMapLoader } from './KakaoMapLoader'

afterEach(() => {
  vi.useRealTimers()
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

  it('removes the failed script and allows the next call to retry', async () => {
    const loader = new KakaoMapLoader()
    const first = loader.load('javascript-key')
    const firstScript = document.head.querySelector<HTMLScriptElement>(
      'script[data-miriyum-kakao-map]',
    )

    firstScript?.dispatchEvent(new Event('error'))
    await expect(first).rejects.toThrow('Kakao 지도 SDK를 불러오지 못했습니다.')
    expect(firstScript === null || document.head.contains(firstScript)).toBe(
      false,
    )

    const second = loader.load('javascript-key')
    const secondScript = document.head.querySelector<HTMLScriptElement>(
      'script[data-miriyum-kakao-map]',
    )
    expect(second).not.toBe(first)
    expect(secondScript).not.toBe(firstScript)

    const maps = { load: vi.fn((callback: () => void) => callback()) }
    window.kakao = {
      maps: maps as unknown as KakaoMapsNamespace,
    }
    secondScript?.dispatchEvent(new Event('load'))

    await expect(second).resolves.toBe(maps)
  })

  it('times out a stalled SDK request and makes it retryable', async () => {
    vi.useFakeTimers()
    const loader = new KakaoMapLoader()
    const load = loader.load('javascript-key')
    const rejection = expect(load).rejects.toThrow(
      'Kakao 지도 SDK 로딩 시간이 초과되었습니다.',
    )

    await vi.advanceTimersByTimeAsync(10_000)

    await rejection
    expect(
      document.head.querySelector('script[data-miriyum-kakao-map]'),
    ).toBeNull()
    expect(loader.load('javascript-key')).not.toBe(load)
  })

  it('times out and retries when the SDK exists but maps.load stalls', async () => {
    vi.useFakeTimers()
    const maps = { load: vi.fn<(_callback: () => void) => void>() }
    window.kakao = {
      maps: maps as unknown as KakaoMapsNamespace,
    }
    const loader = new KakaoMapLoader()
    const first = loader.load('javascript-key')
    let rejection: unknown
    void first.catch((error: unknown) => {
      rejection = error
    })

    expect(vi.getTimerCount()).toBe(1)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(rejection).toEqual(
      new Error('Kakao 지도 SDK 로딩 시간이 초과되었습니다.'),
    )

    maps.load.mockImplementation((callback) => callback())
    await expect(loader.load('javascript-key')).resolves.toBe(maps)
    expect(maps.load).toHaveBeenCalledTimes(2)
  })
})
