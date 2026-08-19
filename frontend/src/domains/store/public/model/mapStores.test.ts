import { describe, expect, it } from 'vitest'
import { storeSummary } from '../test/fixtures'
import { toMapStores, type MapStoreSource } from './mapStores'

describe('toMapStores', () => {
  it('좌표가 없는 page 모드 항목은 좌표 없음으로 넘긴다', () => {
    const items = [storeSummary({ name: '파스타 마스터즈' })]

    const mapStores = toMapStores(items)

    expect(mapStores).toEqual([
      {
        storeId: items[0].storeId,
        name: '1. 파스타 마스터즈',
        coordinates: null,
      },
    ])
  })

  /*
   * 통합 검색이 붙는 날의 계약 모양. 이 함수를 고치지 않고도 좌표가 흐르는지
   * 지금 못박아 둔다.
   */
  it('좌표를 담은 항목은 좌표를 그대로 흘린다', () => {
    const items: MapStoreSource[] = [
      {
        storeId: '01JBQ8Z4T7K2N9V6M3P5R8W1XA',
        name: '미리냠 강남점',
        coordinates: { latitude: 37.4979, longitude: 127.0276 },
      },
    ]

    expect(toMapStores(items)[0].coordinates).toEqual({
      latitude: 37.4979,
      longitude: 127.0276,
    })
  })

  it('명시적 null 좌표도 좌표 없음으로 다룬다', () => {
    const items: MapStoreSource[] = [
      { storeId: 'store-1', name: '좌표 미확인 매장', coordinates: null },
    ]

    expect(toMapStores(items)[0].coordinates).toBeNull()
  })

  it('마커 이름에 목록 순번을 붙여 카드와 대응시킨다', () => {
    const items = [
      storeSummary({ storeId: 'store-1', name: '첫째' }),
      storeSummary({ storeId: 'store-2', name: '둘째' }),
      storeSummary({ storeId: 'store-3', name: '셋째' }),
    ]

    expect(toMapStores(items).map((store) => store.name)).toEqual([
      '1. 첫째',
      '2. 둘째',
      '3. 셋째',
    ])
  })

  it('빈 결과는 빈 목록이 된다', () => {
    expect(toMapStores([])).toEqual([])
  })
})
