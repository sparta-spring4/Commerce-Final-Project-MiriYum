import type { MapCoordinates, MapStore } from '../map/map.types'
import type { StoreSummary } from './searchParams'

/**
 * 검색 결과 → 지도 데이터 경계.
 *
 * 지도가 매장에 대해 아는 모든 것이 이 함수를 통과한다. 화면 어디에서도
 * 검색 응답을 지도 컴포넌트에 직접 넘기지 않는다.
 *
 * ## 좌표가 왜 지금은 항상 null인가
 *
 * page 모드 응답 항목 `StoreSummary`에는 좌표 필드가 없다. 좌표를 가진 계약은
 * cursor 모드의 `IntegratedStoreSearchItem.coordinates` 하나뿐이고, 그 통합
 * 검색은 2차 MVP #112가 자기 query로 따로 소유한다. 그래서 지금 `/stores`가
 * 넘기는 항목은 좌표를 들고 있지 않고, 지도는 `MapFallback`의 "표시할 수 있는
 * 매장 좌표가 없습니다." 상태로 남는다. 좌표를 지어내지 않는다.
 *
 * ## 실제 API가 붙을 때
 *
 * 입력 타입이 좌표를 선택 필드로 받으므로 좌표를 담은 항목이 들어오면 그대로
 * 흐른다. 통합 검색이 연결돼도 **이 함수는 고치지 않는다.** 호출부가 넘기는
 * 항목 타입만 바뀐다.
 */
export type MapStoreSource = Pick<StoreSummary, 'storeId' | 'name'> & {
  /** `IntegratedStoreSearchItem.coordinates`. page 모드 항목에는 없다. */
  coordinates?: MapCoordinates | null
}

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
