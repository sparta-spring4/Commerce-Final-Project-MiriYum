import type { MapStore } from '../map/map.types'
import type { StoreSummary } from './searchParams'

/**
 * 검색 결과 → 지도 데이터 경계.
 *
 * 지도가 매장에 대해 아는 모든 것이 이 함수를 통과한다. 화면 어디에서도
 * 검색 응답을 지도 컴포넌트에 직접 넘기지 않는다.
 *
 * ## 좌표 계약
 *
 * page 모드 `StoreSummary.coordinates`는 현재 주소 버전의 VERIFIED 좌표일 때만
 * 값이 있고 나머지는 null이다. 이 함수는 좌표를 보정하거나 지어내지 않고
 * 서버 응답을 지도 계약으로 좁혀 전달한다.
 */
export type MapStoreSource = Pick<
  StoreSummary,
  'storeId' | 'name' | 'coordinates'
>

export function toMapStores(items: readonly MapStoreSource[]): MapStore[] {
  return items.map((item, index) => ({
    storeId: item.storeId,
    /*
     * 마커 이름에 목록 순번을 붙인다.
     *
     * 마커는 `StoreMapMarker`가 `name`을 title로 쓴다. 순번을 여기서 넣으면
     * 지도 컴포넌트를 건드리지 않고도 "목록 3번 카드 = 지도 3번 마커"가 성립하고,
     * 선택 안내 문구("선택된 매장: 3. …")도 같은 번호를 말한다.
     */
    name: `${mapStoreOrdinal(index)}. ${item.name}`,
    coordinates: item.coordinates ?? null,
  }))
}

/** 목록 카드와 마커가 공유하는 순번. 현재 페이지 안에서만 의미가 있다. */
export function mapStoreOrdinal(index: number): number {
  return index + 1
}
