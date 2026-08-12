export {}

declare global {
  interface Window {
    kakao?: {
      maps: KakaoMapsNamespace
    }
  }

  interface KakaoMapsNamespace {
    load: (callback: () => void) => void
    LatLng: new (latitude: number, longitude: number) => KakaoLatLng
    Map: new (
      container: HTMLElement,
      options: { center: KakaoLatLng },
    ) => KakaoMapInstance
    Marker: new (options: {
      map: KakaoMapInstance
      position: KakaoLatLng
      title: string
    }) => KakaoMarkerInstance
    CustomOverlay: new (options: {
      position: KakaoLatLng
      content: HTMLElement
      yAnchor: number
      zIndex: number
    }) => KakaoCustomOverlayInstance
    event: {
      addListener: (
        target: KakaoMarkerInstance,
        event: 'click',
        listener: () => void,
      ) => void
      removeListener: (
        target: KakaoMarkerInstance,
        event: 'click',
        listener: () => void,
      ) => void
    }
  }

  interface KakaoMapInstance {
    setCenter: (position: KakaoLatLng) => void
    relayout: () => void
  }

  interface KakaoLatLng {}

  interface KakaoMarkerInstance {
    setMap: (map: KakaoMapInstance | null) => void
    setPosition: (position: KakaoLatLng) => void
    setTitle: (title: string) => void
    setZIndex: (zIndex: number) => void
  }

  interface KakaoCustomOverlayInstance {
    setMap: (map: KakaoMapInstance | null) => void
    setPosition: (position: KakaoLatLng) => void
  }
}
