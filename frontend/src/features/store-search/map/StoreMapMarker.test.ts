import '@testing-library/jest-dom/vitest'
import { describe, expect, it, vi } from 'vitest'

import { StoreMapMarker } from './StoreMapMarker'
import type { MapStore } from './map.types'

class FakeLatLng {
  constructor(
    readonly latitude: number,
    readonly longitude: number,
  ) {}
}

class FakeMarker {
  readonly setMap = vi.fn()
  readonly setPosition = vi.fn()
  readonly setTitle = vi.fn()
  readonly setZIndex = vi.fn()

  constructor(
    readonly options: {
      map: KakaoMapInstance
      position: KakaoLatLng
      title: string
    },
  ) {}
}

class FakeCustomOverlay {
  readonly setMap = vi.fn()
  readonly setPosition = vi.fn()

  constructor(
    readonly options: {
      position: KakaoLatLng
      content: HTMLElement
      yAnchor: number
      zIndex: number
    },
  ) {}
}

function store(overrides: Partial<MapStore> = {}): MapStore {
  return {
    storeId: 'store-1',
    name: '미리냠 성수점',
    latitude: 37.5445,
    longitude: 127.056,
    ...overrides,
  }
}

describe('StoreMapMarker', () => {
  it('updates the marker, exposes selection without color alone, and releases its listener', () => {
    let clickListener: (() => void) | undefined
    const removeListener = vi.fn()
    const addListener = vi.fn(
      (_target: KakaoMarkerInstance, _event: 'click', listener: () => void) => {
        clickListener = listener
      },
    )
    const markerInstances: FakeMarker[] = []
    const overlayInstances: FakeCustomOverlay[] = []
    const maps = {
      LatLng: FakeLatLng,
      Marker: class extends FakeMarker {
        constructor(options: ConstructorParameters<typeof FakeMarker>[0]) {
          super(options)
          markerInstances.push(this)
        }
      },
      CustomOverlay: class extends FakeCustomOverlay {
        constructor(options: ConstructorParameters<typeof FakeCustomOverlay>[0]) {
          super(options)
          overlayInstances.push(this)
        }
      },
      event: { addListener, removeListener },
    } as unknown as KakaoMapsNamespace
    const map = {} as KakaoMapInstance
    const onSelect = vi.fn()
    const marker = new StoreMapMarker(maps, map, store(), onSelect)
    const instance = markerInstances[0]
    const selectedOverlay = overlayInstances[0]

    expect(instance?.options.title).toBe('미리냠 성수점')
    expect(selectedOverlay?.options.content).toHaveTextContent('선택됨')
    expect(selectedOverlay?.options.content).toHaveAttribute(
      'aria-label',
      '선택된 매장',
    )
    clickListener?.()
    expect(onSelect).toHaveBeenCalledWith('store-1')

    marker.update(
      store({ name: '미리냠 새 지점', latitude: 35.1796, longitude: 129.0756 }),
    )
    expect(instance?.setPosition).toHaveBeenCalledWith(
      expect.objectContaining({ latitude: 35.1796, longitude: 129.0756 }),
    )
    expect(instance?.setTitle).toHaveBeenCalledWith('미리냠 새 지점')
    expect(selectedOverlay?.setPosition).toHaveBeenCalledWith(
      expect.objectContaining({ latitude: 35.1796, longitude: 129.0756 }),
    )

    marker.setSelected(true)
    expect(instance?.setZIndex).toHaveBeenLastCalledWith(10)
    expect(instance?.setTitle).toHaveBeenLastCalledWith('선택됨: 미리냠 새 지점')
    expect(selectedOverlay?.setMap).toHaveBeenLastCalledWith(map)

    marker.setSelected(false)
    expect(selectedOverlay?.setMap).toHaveBeenLastCalledWith(null)

    marker.destroy()
    expect(removeListener).toHaveBeenCalledWith(instance, 'click', clickListener)
    expect(instance?.setMap).toHaveBeenCalledWith(null)
    expect(selectedOverlay?.setMap).toHaveBeenLastCalledWith(null)
  })
})
