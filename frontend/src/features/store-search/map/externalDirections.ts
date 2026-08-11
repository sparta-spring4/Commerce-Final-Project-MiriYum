import type { MapStore } from './map.types'
import { hasValidCoordinates } from './map.types'

const KAKAO_DIRECTIONS_BASE_URL = 'https://map.kakao.com/link/to'

export function buildKakaoDirectionsUrl(
  destination: Pick<MapStore, 'name' | 'latitude' | 'longitude'>,
): string {
  if (!hasValidCoordinates(destination)) {
    throw new Error('유효한 매장 좌표가 필요합니다.')
  }

  return `${KAKAO_DIRECTIONS_BASE_URL}/${encodeURIComponent(destination.name)},${destination.latitude},${destination.longitude}`
}
