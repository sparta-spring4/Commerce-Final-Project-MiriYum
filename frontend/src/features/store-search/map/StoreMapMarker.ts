import type { MapStore } from './map.types'

export class StoreMapMarker {
  private store: MapStore
  private readonly map: KakaoMapInstance
  private readonly marker: KakaoMarkerInstance
  private readonly selectedOverlay: KakaoCustomOverlayInstance
  private readonly clickListener: () => void

  constructor(
    private readonly maps: KakaoMapsNamespace,
    map: KakaoMapInstance,
    store: MapStore,
    onSelect: (storeId: string) => void,
  ) {
    this.store = store
    this.map = map
    const position = new maps.LatLng(store.latitude, store.longitude)
    this.marker = new maps.Marker({
      map,
      position,
      title: store.name,
    })
    const selectedLabel = document.createElement('span')
    selectedLabel.textContent = '선택됨'
    selectedLabel.setAttribute('aria-label', '선택된 매장')
    Object.assign(selectedLabel.style, {
      display: 'inline-block',
      padding: '2px 6px',
      border: '2px solid #111827',
      borderRadius: '999px',
      background: '#ffffff',
      color: '#111827',
      fontSize: '12px',
      fontWeight: '700',
      whiteSpace: 'nowrap',
    })
    this.selectedOverlay = new maps.CustomOverlay({
      position,
      content: selectedLabel,
      yAnchor: 2,
      zIndex: 11,
    })
    this.clickListener = () => onSelect(this.store.storeId)
    maps.event.addListener(this.marker, 'click', this.clickListener)
  }

  update(store: MapStore): void {
    this.store = store
    const position = new this.maps.LatLng(store.latitude, store.longitude)
    this.marker.setPosition(position)
    this.selectedOverlay.setPosition(position)
    this.marker.setTitle(store.name)
  }

  setSelected(selected: boolean): void {
    this.marker.setZIndex(selected ? 10 : 1)
    this.marker.setTitle(
      selected ? `선택됨: ${this.store.name}` : this.store.name,
    )
    this.selectedOverlay.setMap(selected ? this.map : null)
  }

  destroy(): void {
    this.maps.event.removeListener(this.marker, 'click', this.clickListener)
    this.selectedOverlay.setMap(null)
    this.marker.setMap(null)
  }
}
