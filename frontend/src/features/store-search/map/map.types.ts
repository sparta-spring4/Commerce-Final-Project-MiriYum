export type MapStore = {
  storeId: string
  name: string
  latitude: number
  longitude: number
}

export type KakaoMapProps = {
  stores: MapStore[]
  selectedStoreId: string | null
  onSelectStore: (storeId: string) => void
}

export function hasValidCoordinates(
  store: Pick<MapStore, 'latitude' | 'longitude'>,
): boolean {
  return (
    Number.isFinite(store.latitude) &&
    Number.isFinite(store.longitude) &&
    store.latitude >= -90 &&
    store.latitude <= 90 &&
    store.longitude >= -180 &&
    store.longitude <= 180
  )
}
