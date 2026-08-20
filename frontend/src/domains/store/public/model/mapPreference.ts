/**
 * 매장 찾기에서 지도를 열어 둘지에 대한 사용자 기본값.
 *
 * 기본은 목록만 보는 것이다. 지도는 위치가 궁금할 때 여는 도구이지 항상 자리를
 * 차지해야 하는 것이 아니다. 다만 지도를 놓고 매장을 훑는 편이 편한 사용자도
 * 있어서, 한 번 열어 둔 선택을 다음 방문까지 기억한다.
 *
 * 보존 위치는 localStorage다. `CurrentStoreProvider`의 sessionStorage와 달리
 * 여기 담기는 것은 "이번 세션에 고른 매장"이 아니라 "내가 쓰는 기본 보기"라서
 * 탭을 닫아도 남아야 뜻이 있다. 토큰이 아니라 화면 선택 상태다.
 */
const STORAGE_KEY = 'MIRIYUM_STORE_SEARCH_MAP_OPEN'

export function readMapOpenPreference(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === 'true'
  } catch {
    // 저장소 접근이 막힌 환경에서도 화면은 동작해야 한다. 기본값으로 연다.
    return false
  }
}

export function writeMapOpenPreference(isOpen: boolean): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, isOpen ? 'true' : 'false')
  } catch {
    // 저장 실패는 기능 실패가 아니다. 이번 화면 상태만으로 계속 진행한다.
  }
}
