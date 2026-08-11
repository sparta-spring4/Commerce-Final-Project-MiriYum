import '@testing-library/jest-dom/vitest'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { MapStore } from './map.types'

const mocks = vi.hoisted(() => ({
  load: vi.fn(),
  markerConstruct: vi.fn(),
  markerUpdate: vi.fn(),
  markerSetSelected: vi.fn(),
  markerDestroy: vi.fn(),
}))

vi.mock('./KakaoMapLoader', () => ({
  kakaoMapLoader: { load: mocks.load },
}))

vi.mock('./StoreMapMarker', () => ({
  StoreMapMarker: class {
    constructor(...args: unknown[]) {
      mocks.markerConstruct(...args)
    }

    update(store: MapStore) {
      mocks.markerUpdate(store)
    }

    setSelected(selected: boolean) {
      mocks.markerSetSelected(selected)
    }

    destroy() {
      mocks.markerDestroy()
    }
  },
}))

import { KakaoMap } from './KakaoMap'

const stores: MapStore[] = [
  {
    storeId: 'store-1',
    name: '미리냠 강남점',
    latitude: 37.4979,
    longitude: 127.0276,
  },
  {
    storeId: 'store-2',
    name: '미리냠 역삼점',
    latitude: 37.5007,
    longitude: 127.0365,
  },
]

function createMaps() {
  const setCenter = vi.fn()
  const relayout = vi.fn()
  const Map = vi.fn(function FakeMap() {
    return { setCenter, relayout }
  })
  const LatLng = vi.fn(function FakeLatLng(
    this: { latitude: number; longitude: number },
    latitude: number,
    longitude: number,
  ) {
    this.latitude = latitude
    this.longitude = longitude
  })

  return {
    maps: { Map, LatLng } as unknown as KakaoMapsNamespace,
    Map,
    LatLng,
    setCenter,
  }
}

describe('KakaoMap', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_KAKAO_MAP_APP_KEY', 'test-key')
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllEnvs()
  })

  it('loads the SDK and creates markers only for stores with valid coordinates', async () => {
    const { maps, Map, LatLng } = createMaps()
    mocks.load.mockResolvedValue(maps)
    const onSelectStore = vi.fn()

    render(
      <KakaoMap
        stores={[
          ...stores,
          { ...stores[0], storeId: 'invalid', latitude: Number.NaN },
        ]}
        selectedStoreId="store-2"
        onSelectStore={onSelectStore}
      />,
    )

    await waitFor(() => expect(Map).toHaveBeenCalledOnce())
    expect(mocks.load).toHaveBeenCalledWith('test-key')
    expect(screen.getByLabelText('검색 결과 지도')).toHaveStyle({
      width: '100%',
      minHeight: '320px',
    })
    expect(LatLng).toHaveBeenCalledWith(37.5007, 127.0365)
    expect(mocks.markerConstruct).toHaveBeenCalledTimes(2)
    expect(screen.getByText('좌표를 확인할 수 없는 매장 1곳')).toBeVisible()
  })

  it('updates marker selection and recenters when the selected store changes', async () => {
    const { maps, setCenter } = createMaps()
    mocks.load.mockResolvedValue(maps)
    const { rerender } = render(
      <KakaoMap
        stores={stores}
        selectedStoreId="store-1"
        onSelectStore={vi.fn()}
      />,
    )
    await waitFor(() => expect(mocks.markerConstruct).toHaveBeenCalledTimes(2))
    mocks.markerSetSelected.mockClear()

    rerender(
      <KakaoMap
        stores={stores}
        selectedStoreId="store-2"
        onSelectStore={vi.fn()}
      />,
    )

    await waitFor(() => expect(setCenter).toHaveBeenCalled())
    expect(mocks.markerSetSelected).toHaveBeenCalledWith(false)
    expect(mocks.markerSetSelected).toHaveBeenCalledWith(true)
    expect(screen.getByRole('status')).toHaveTextContent(
      '선택된 매장: 미리냠 역삼점',
    )
  })

  it('updates retained markers and destroys removed markers', async () => {
    const { maps } = createMaps()
    mocks.load.mockResolvedValue(maps)
    const { rerender } = render(
      <KakaoMap
        stores={stores}
        selectedStoreId={null}
        onSelectStore={vi.fn()}
      />,
    )
    await waitFor(() => expect(mocks.markerConstruct).toHaveBeenCalledTimes(2))
    mocks.markerUpdate.mockClear()

    rerender(
      <KakaoMap
        stores={[{ ...stores[0], name: '이름 변경 매장' }]}
        selectedStoreId={null}
        onSelectStore={vi.fn()}
      />,
    )

    await waitFor(() => expect(mocks.markerDestroy).toHaveBeenCalledOnce())
    expect(mocks.markerUpdate).toHaveBeenCalledWith(
      expect.objectContaining({ storeId: 'store-1', name: '이름 변경 매장' }),
    )
  })

  it('destroys every marker when the map unmounts', async () => {
    const { maps } = createMaps()
    mocks.load.mockResolvedValue(maps)
    const { unmount } = render(
      <KakaoMap
        stores={stores}
        selectedStoreId={null}
        onSelectStore={vi.fn()}
      />,
    )
    await waitFor(() => expect(mocks.markerConstruct).toHaveBeenCalledTimes(2))

    unmount()

    expect(mocks.markerDestroy).toHaveBeenCalledTimes(2)
  })

  it('reattaches markers to a new map after coordinates become valid again', async () => {
    const { maps, Map } = createMaps()
    mocks.load.mockResolvedValue(maps)
    const { rerender } = render(
      <KakaoMap
        stores={stores}
        selectedStoreId={null}
        onSelectStore={vi.fn()}
      />,
    )
    await waitFor(() => expect(mocks.markerConstruct).toHaveBeenCalledTimes(2))

    rerender(
      <KakaoMap
        stores={stores.map((store) => ({ ...store, latitude: 91 }))}
        selectedStoreId={null}
        onSelectStore={vi.fn()}
      />,
    )
    expect(screen.getByText('표시할 수 있는 매장 좌표가 없습니다.')).toBeVisible()

    rerender(
      <KakaoMap
        stores={stores}
        selectedStoreId={null}
        onSelectStore={vi.fn()}
      />,
    )

    await waitFor(() => expect(Map).toHaveBeenCalledTimes(2))
    expect(mocks.markerConstruct).toHaveBeenCalledTimes(4)
    const secondMap = Map.mock.results[1]?.value
    expect(mocks.markerConstruct.mock.calls[2]?.[1]).toBe(secondMap)
    expect(mocks.markerConstruct.mock.calls[3]?.[1]).toBe(secondMap)
  })

  it('shows a list-preserving fallback when the SDK fails', async () => {
    mocks.load.mockRejectedValue(new Error('SDK failure'))

    render(
      <KakaoMap
        stores={stores}
        selectedStoreId={null}
        onSelectStore={vi.fn()}
      />,
    )

    expect(
      await screen.findByText('지도를 불러오지 못했습니다.'),
    ).toBeVisible()
    expect(
      screen.getByText('매장 목록에서 계속 확인할 수 있습니다.'),
    ).toBeVisible()
  })

  it('does not load the SDK when no valid coordinates exist', async () => {
    render(
      <KakaoMap
        stores={[{ ...stores[0], latitude: 91 }]}
        selectedStoreId={null}
        onSelectStore={vi.fn()}
      />,
    )

    expect(screen.getByText('표시할 수 있는 매장 좌표가 없습니다.')).toBeVisible()
    await act(async () => {})
    expect(mocks.load).not.toHaveBeenCalled()
  })
})
