import type { ManagedMenu, MenuVersion } from './types'

/**
 * 메뉴 목록 필터.
 *
 * 계약은 한 메뉴가 초안·게시 예약·게시 버전을 동시에 가질 수 있게 한다. 그래서
 * 필터는 "메뉴의 상태"가 아니라 "그 상태의 버전을 가지고 있는지"로 판정한다.
 * 운영 종료는 버전이 아니라 메뉴 단위 `retired` 값이다.
 */
export type MenuFilter = 'ALL' | 'PUBLISHED' | 'SCHEDULED' | 'DRAFT' | 'RETIRED'

export const MENU_FILTER_LABEL: Record<MenuFilter, string> = {
  ALL: '전체',
  PUBLISHED: '게시',
  SCHEDULED: '게시 예약',
  DRAFT: '초안',
  RETIRED: '운영 종료',
}

export const MENU_FILTERS: readonly MenuFilter[] = [
  'ALL',
  'PUBLISHED',
  'SCHEDULED',
  'DRAFT',
  'RETIRED',
]

export function matchesMenuFilter(
  menu: ManagedMenu,
  filter: MenuFilter,
): boolean {
  switch (filter) {
    case 'ALL':
      return true
    case 'RETIRED':
      return menu.retired
    case 'PUBLISHED':
      return !menu.retired && menu.published != null
    case 'SCHEDULED':
      return !menu.retired && menu.scheduled != null
    case 'DRAFT':
      return !menu.retired && menu.draft != null
  }
}

/**
 * 목록에 이름·가격을 보여 줄 대표 버전.
 *
 * 게시된 버전이 고객에게 보이는 값이므로 먼저 쓴다. 없으면 게시 예약, 그다음
 * 초안이다. 세 슬롯이 모두 비면 표시할 내용이 없다는 뜻이고 값을 지어내지 않는다.
 */
export function primaryMenuVersion(menu: ManagedMenu): MenuVersion | null {
  return menu.published ?? menu.scheduled ?? menu.draft ?? null
}

/** 편집을 시작할 버전. 초안이 있으면 초안이 최신 작업본이다. */
export function editableMenuVersion(menu: ManagedMenu): MenuVersion | null {
  return menu.draft ?? menu.published ?? menu.scheduled ?? null
}
