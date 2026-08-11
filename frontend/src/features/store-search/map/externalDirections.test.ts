import { describe, expect, it } from 'vitest'

import { buildKakaoDirectionsUrl } from './externalDirections'

describe('buildKakaoDirectionsUrl', () => {
  it('encodes the server-provided store name and coordinates in a Kakao map destination URL', () => {
    expect(
      buildKakaoDirectionsUrl({
        name: '미리냠 성수점',
        latitude: 37.5445,
        longitude: 127.056,
      }),
    ).toBe(
      'https://map.kakao.com/link/to/%EB%AF%B8%EB%A6%AC%EB%83%A0%20%EC%84%B1%EC%88%98%EC%A0%90,37.5445,127.056',
    )
  })

  it.each([
    { latitude: Number.NaN, longitude: 127.056 },
    { latitude: 91, longitude: 127.056 },
    { latitude: 37.5445, longitude: -181 },
  ])('rejects an invalid destination coordinate: %o', (destination) => {
    expect(() =>
      buildKakaoDirectionsUrl({ name: '잘못된 매장', ...destination }),
    ).toThrow('유효한 매장 좌표가 필요합니다.')
  })
})
