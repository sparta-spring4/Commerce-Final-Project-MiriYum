import type { components } from '../../../../shared/api/generated/waiting'

type GeneratedWaitingLocationProofRequest =
  components['schemas']['WaitingLocationProofRequest']

export type WaitingLocationMeasurement =
  | {
      measurementStatus: 'MEASURED'
      latitude: number
      longitude: number
      accuracyMeters: number
      measuredAt: string
      integrityStatus: GeneratedWaitingLocationProofRequest['integrityStatus']
    }
  | {
      measurementStatus: 'PERMISSION_DENIED' | 'POSITION_UNAVAILABLE'
      integrityStatus: GeneratedWaitingLocationProofRequest['integrityStatus']
    }

export function measureWaitingLocation(
  geolocation: Geolocation | undefined,
): Promise<WaitingLocationMeasurement> {
  if (geolocation === undefined) {
    return Promise.resolve({
      measurementStatus: 'POSITION_UNAVAILABLE',
      integrityStatus: 'CLEAR',
    })
  }

  return new Promise((resolve) => {
    geolocation.getCurrentPosition(
      (position) => {
        resolve({
          measurementStatus: 'MEASURED',
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracyMeters: position.coords.accuracy,
          measuredAt: new Date(position.timestamp).toISOString(),
          integrityStatus: 'CLEAR',
        })
      },
      (error) => {
        resolve({
          measurementStatus:
            error.code === error.PERMISSION_DENIED
              ? 'PERMISSION_DENIED'
              : 'POSITION_UNAVAILABLE',
          integrityStatus: 'CLEAR',
        })
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 10_000 },
    )
  })
}
