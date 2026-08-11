import type { MapStore } from './map.types'

export class StoreMapMarker {
  private store: MapStore
  private readonly marker: KakaoMarkerInstance
  private readonly clickListener: () => void

  constructor(
    private readonly maps: KakaoMapsNamespace,
    map: KakaoMapInstance,
    store: MapStore,
    onSelect: (storeId: string) => void,
  ) {
    this.store = store
    this.marker = new maps.Marker({
      map,
      position: new maps.LatLng(store.latitude, store.longitude),
      title: store.name,
    })
    this.clickListener = () => onSelect(this.store.storeId)
    maps.event.addListener(this.marker, 'click', this.clickListener)
  }

  update(store: MapStore): void {
    this.store = store
    this.marker.setPosition(new this.maps.LatLng(store.latitude, store.longitude))
    this.marker.setTitle(store.name)
  }

  setSelected(selected: boolean): void {
    this.marker.setZIndex(selected ? 10 : 1)
    this.marker.setTitle(
      selected ? `선택됨: ${this.store.name}` : this.store.name,
    )
  }

  destroy(): void {
    this.maps.event.removeListener(this.marker, 'click', this.clickListener)
    this.marker.setMap(null)
  }
}
