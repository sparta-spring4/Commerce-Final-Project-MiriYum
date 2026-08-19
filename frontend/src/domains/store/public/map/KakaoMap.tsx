import { useEffect, useMemo, useRef, useState } from 'react'

import { kakaoMapLoader } from './KakaoMapLoader'
import { MapFallback } from './MapFallback'
import { StoreMapMarker } from './StoreMapMarker'
import type { KakaoMapProps, MappableStore } from './map.types'
import { hasValidCoordinates } from './map.types'

type MapRuntime = {
  maps: KakaoMapsNamespace
  map: KakaoMapInstance
  container: HTMLElement
}

export function KakaoMap({
  stores,
  selectedStoreId,
  onSelectStore,
}: KakaoMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const markersRef = useRef(new Map<string, StoreMapMarker>())
  const storesRef = useRef(stores)
  const selectedStoreIdRef = useRef(selectedStoreId)
  const onSelectStoreRef = useRef(onSelectStore)
  const [runtime, setRuntime] = useState<MapRuntime | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const validStores = useMemo(
    () => stores.filter(hasValidCoordinates),
    [stores],
  )
  const invalidStoreCount = stores.length - validStores.length
  const hasValidStores = validStores.length > 0
  const appKey = import.meta.env.VITE_KAKAO_MAP_APP_KEY ?? ''
  const selectedStore = validStores.find(
    (store: MappableStore) => store.storeId === selectedStoreId,
  )
  const selectedStoreWithoutCoordinates = stores.some(
    (store) =>
      store.storeId === selectedStoreId && !hasValidCoordinates(store),
  )

  storesRef.current = stores
  selectedStoreIdRef.current = selectedStoreId
  onSelectStoreRef.current = onSelectStore

  useEffect(() => {
    if (!hasValidStores || containerRef.current === null) {
      setRuntime(null)
      setLoadError(false)
      return
    }

    let active = true
    setRuntime(null)
    setLoadError(false)

    void kakaoMapLoader
      .load(appKey)
      .then((maps) => {
        if (!active || containerRef.current === null) {
          return
        }

        const currentStores = storesRef.current.filter(hasValidCoordinates)
        const centerStore =
          currentStores.find(
            (store) => store.storeId === selectedStoreIdRef.current,
          ) ?? currentStores[0]

        if (centerStore === undefined) {
          return
        }

        const container = containerRef.current
        const map = new maps.Map(container, {
          center: new maps.LatLng(
            centerStore.coordinates.latitude,
            centerStore.coordinates.longitude,
          ),
        })
        setRuntime({ maps, map, container })
      })
      .catch(() => {
        if (active) {
          setLoadError(true)
        }
      })

    return () => {
      active = false
      for (const marker of markersRef.current.values()) {
        marker.destroy()
      }
      markersRef.current.clear()
    }
  }, [appKey, hasValidStores, loadAttempt])

  useEffect(() => {
    if (runtime === null || runtime.container !== containerRef.current) {
      return
    }

    const storesById = new Map(
      validStores.map((store) => [store.storeId, store]),
    )

    for (const [storeId, marker] of markersRef.current) {
      const store = storesById.get(storeId)
      if (store === undefined) {
        marker.destroy()
        markersRef.current.delete(storeId)
        continue
      }
      marker.update(store)
    }

    for (const store of validStores) {
      if (!markersRef.current.has(store.storeId)) {
        markersRef.current.set(
          store.storeId,
          new StoreMapMarker(runtime.maps, runtime.map, store, (storeId) =>
            onSelectStoreRef.current(storeId),
          ),
        )
      }
    }

    for (const [storeId, marker] of markersRef.current) {
      marker.setSelected(storeId === selectedStoreId)
    }

    const selectedStore = validStores.find(
      (store) => store.storeId === selectedStoreId,
    )
    if (selectedStore !== undefined) {
      runtime.map.setCenter(
        new runtime.maps.LatLng(
          selectedStore.coordinates.latitude,
          selectedStore.coordinates.longitude,
        ),
      )
    }
    runtime.map.relayout()
  }, [runtime, selectedStoreId, validStores])

  useEffect(() => {
    if (
      runtime === null ||
      runtime.container !== containerRef.current ||
      !hasValidStores ||
      typeof ResizeObserver === 'undefined'
    ) {
      return
    }

    const observer = new ResizeObserver(() => runtime.map.relayout())
    observer.observe(runtime.container)

    return () => observer.disconnect()
  }, [hasValidStores, runtime])

  if (stores.length === 0) {
    return (
      <MapFallback
        reason="검색 결과가 없습니다."
        guidance="검색 조건을 변경해 다시 확인해 주세요."
      />
    )
  }

  if (!hasValidStores) {
    return (
      <MapFallback
        reason={
          selectedStoreWithoutCoordinates
            ? '선택한 매장은 지도에 표시할 수 없습니다.'
            : '표시할 수 있는 매장 좌표가 없습니다.'
        }
      />
    )
  }

  if (appKey.trim() === '') {
    return (
      <MapFallback
        reason="지도 설정이 필요합니다."
        guidance="Kakao 지도 JavaScript 키를 확인해 주세요."
      />
    )
  }

  if (loadError) {
    return (
      <MapFallback
        reason="지도를 불러오지 못했습니다."
        onRetry={() => {
          setLoadError(false)
          setRuntime(null)
          setLoadAttempt((attempt) => attempt + 1)
        }}
      />
    )
  }

  return (
    <section aria-label="매장 지도">
      {runtime === null ? <p role="status">지도를 불러오는 중입니다.</p> : null}
      <div
        ref={containerRef}
        aria-label="검색 결과 지도"
        style={{ width: '100%', minHeight: 320 }}
      />
      {selectedStore !== undefined ? (
        <p role="status">선택된 매장: {selectedStore.name}</p>
      ) : null}
      {selectedStoreWithoutCoordinates ? (
        <p role="status">선택한 매장은 지도에 표시할 수 없습니다.</p>
      ) : null}
      {invalidStoreCount > 0 ? (
        <p>좌표를 확인할 수 없는 매장 {invalidStoreCount}곳</p>
      ) : null}
    </section>
  )
}
