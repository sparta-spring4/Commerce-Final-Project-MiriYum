import { describe, expect, it } from 'vitest'
import { measureWaitingLocation } from './locationMeasurement'

describe('웨이팅 등록 위치 측정', () => {
  it('브라우저 측정값과 timestamp를 위치 증빙 요청으로 변환한다', async () => {
    let options: PositionOptions | undefined
    const geolocation = {
      getCurrentPosition(
        success: PositionCallback,
        _failure: PositionErrorCallback,
        receivedOptions?: PositionOptions,
      ) {
        options = receivedOptions
        success({
          coords: {
            latitude: 37.5665,
            longitude: 126.978,
            accuracy: 24.5,
            altitude: null,
            altitudeAccuracy: null,
            heading: null,
            speed: null,
            toJSON: () => ({}),
          },
          timestamp: Date.parse('2026-08-20T01:02:03.456Z'),
          toJSON: () => ({}),
        })
      },
    } as unknown as Geolocation

    await expect(measureWaitingLocation(geolocation)).resolves.toEqual({
      measurementStatus: 'MEASURED',
      latitude: 37.5665,
      longitude: 126.978,
      accuracyMeters: 24.5,
      measuredAt: '2026-08-20T01:02:03.456Z',
      integrityStatus: 'CLEAR',
    })
    expect(options).toEqual({
      enableHighAccuracy: true,
      maximumAge: 0,
      timeout: 10_000,
    })
  })

  it('브라우저 권한 거부를 좌표 없는 PERMISSION_DENIED 요청으로 변환한다', async () => {
    const geolocation = {
      getCurrentPosition(_success: PositionCallback, failure: PositionErrorCallback) {
        failure({
          code: 1,
          message: 'permission denied',
          PERMISSION_DENIED: 1,
          POSITION_UNAVAILABLE: 2,
          TIMEOUT: 3,
        })
      },
    } as unknown as Geolocation

    await expect(measureWaitingLocation(geolocation)).resolves.toEqual({
      measurementStatus: 'PERMISSION_DENIED',
      integrityStatus: 'CLEAR',
    })
  })

  it('위치 기능을 사용할 수 없으면 좌표 없는 POSITION_UNAVAILABLE 요청을 만든다', async () => {
    await expect(measureWaitingLocation(undefined)).resolves.toEqual({
      measurementStatus: 'POSITION_UNAVAILABLE',
      integrityStatus: 'CLEAR',
    })
  })
})
