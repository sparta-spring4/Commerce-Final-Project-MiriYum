import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  readMapOpenPreference,
  writeMapOpenPreference,
} from './mapPreference'

afterEach(() => {
  // 먼저 진짜 localStorage를 되돌린다. 대역에는 clear가 없다.
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

describe('지도 기본 보기 설정', () => {
  it('저장한 적이 없으면 지도를 열지 않는다', () => {
    expect(readMapOpenPreference()).toBe(false)
  })

  it('열어 둔 선택을 다음에 그대로 돌려준다', () => {
    writeMapOpenPreference(true)

    expect(readMapOpenPreference()).toBe(true)
  })

  it('닫은 선택도 기억한다', () => {
    writeMapOpenPreference(true)
    writeMapOpenPreference(false)

    expect(readMapOpenPreference()).toBe(false)
  })

  /*
   * Safari 비공개 모드처럼 저장소 접근 자체가 예외를 던지는 환경이 있다.
   * 화면이 거기서 깨지면 검색 자체를 못 쓴다.
   */
  it('저장소를 못 읽어도 화면이 멈추지 않는다', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('저장소 접근이 거부되었습니다.')
      },
      setItem: () => {
        throw new Error('저장소 접근이 거부되었습니다.')
      },
    })

    expect(readMapOpenPreference()).toBe(false)
    expect(() => writeMapOpenPreference(true)).not.toThrow()
  })
})
