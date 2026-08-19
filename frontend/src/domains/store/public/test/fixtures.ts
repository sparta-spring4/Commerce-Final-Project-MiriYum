import type {
  CatalogItem,
  PublicMenu,
  StoreDetail,
  StoreSummary,
} from '../model/searchParams'

/**
 * 테스트 고정 데이터.
 *
 * 모양은 생성 타입이 강제한다. 계약에 없는 필드를 넣으면 typecheck가 막는다.
 */

export const STORE_CATEGORIES: CatalogItem[] = [
  { code: 'KOREAN', displayName: '한식' },
  { code: 'ITALIAN', displayName: '이탈리안' },
]

export const STORE_TAGS: CatalogItem[] = [
  { code: 'DATE_COURSE', displayName: '데이트' },
]

export const MENU_CATEGORIES: CatalogItem[] = [
  { code: 'PASTA', displayName: '파스타' },
]

export function storeSummary(
  overrides: Partial<StoreSummary> = {},
): StoreSummary {
  return {
    storeId: '01JBQ8Z4T7K2N9V6M3P5R8W1XA',
    name: '파스타 마스터즈',
    region: 'SEOUL',
    address: '서울특별시 성동구 연무장길 14',
    storeCategoryCode: 'ITALIAN',
    operationStatus: 'OPEN',
    modes: {
      reservationEnabled: true,
      menuHoldEnabled: true,
      pickupEnabled: false,
    },
    reservationAvailability: 'NOT_REQUESTED',
    ...overrides,
  }
}

export function publicMenu(overrides: Partial<PublicMenu> = {}): PublicMenu {
  return {
    menuId: '01JBQ8Z4T7K2N9V6M3P5R8W1MA',
    name: '트러플 크림 파파델레',
    description: '자가제면 파파델레와 포르치니 크림',
    imageUrl: null,
    price: 32000,
    representative: true,
    primaryCategoryCode: 'PASTA',
    secondaryCategoryCodes: [],
    localTags: ['시그니처'],
    holdEnabled: true,
    pickupEnabled: false,
    saleStatus: 'SELLING',
    ...overrides,
  }
}

export function storeDetail(overrides: Partial<StoreDetail> = {}): StoreDetail {
  return {
    storeId: '01JBQ8Z4T7K2N9V6M3P5R8W1XA',
    name: '파스타 마스터즈',
    description: '자가제면 파스타를 내는 이탈리안 레스토랑입니다.',
    region: 'SEOUL',
    address: '서울특별시 성동구 연무장길 14',
    timeZoneId: 'Asia/Seoul',
    storeCategoryCode: 'ITALIAN',
    tags: ['DATE_COURSE'],
    operationStatus: 'OPEN',
    modes: {
      reservationEnabled: true,
      menuHoldEnabled: true,
      pickupEnabled: false,
    },
    operatingHours: [
      {
        dayOfWeek: 'MONDAY',
        businessHours: [{ startTime: '11:30', endTime: '22:00' }],
        breakTimes: [{ startTime: '15:00', endTime: '17:00' }],
      },
    ],
    reservationTimeSlots: [
      {
        dayOfWeek: 'MONDAY',
        slots: [{ startTime: '11:30', endTime: '14:30' }],
      },
    ],
    representativeMenus: [publicMenu()],
    reservationAvailability: 'NOT_REQUESTED',
    ...overrides,
  }
}

export function storePage(items: StoreSummary[], totalElements = items.length) {
  return {
    items,
    page: {
      number: 0,
      size: 20,
      totalElements,
      totalPages: Math.max(1, Math.ceil(totalElements / 20)),
      hasNext: totalElements > 20,
    },
  }
}
