export type MapStore = {
  storeId: string
  name: string
  coordinates: MapCoordinates | null
}

export type MapCoordinates = {
  latitude: number
  longitude: number
}

export type MappableStore = MapStore & {
  coordinates: MapCoordinates
}

export type KakaoMapProps = {
  stores: MapStore[]
  selectedStoreId: string | null
  onSelectStore: (storeId: string) => void
}

export function hasValidCoordinates<T extends Pick<MapStore, 'coordinates'>>(
  store: T,
): store is T & { coordinates: MapCoordinates } {
  const coordinates = store.coordinates
  return (
    coordinates !== null &&
    Number.isFinite(coordinates.latitude) &&
    Number.isFinite(coordinates.longitude) &&
    coordinates.latitude >= -90 &&
    coordinates.latitude <= 90 &&
    coordinates.longitude >= -180 &&
    coordinates.longitude <= 180
  )
}
