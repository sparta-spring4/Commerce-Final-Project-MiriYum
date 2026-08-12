import { http } from 'msw'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { AuthErrorCode } from '../../auth/model/authErrors'
import type { ManagedMenu, ManagedStore, MenuVersion } from '../model/types'

/**
 * 매장 운영자 화면 테스트용 MSW 핸들러와 고정 데이터.
 *
 * 셸이 렌더될 때마다 재발급을 시도하므로 재발급 핸들러는 모든 화면 테스트에
 * 필요하다. 경로 문자열을 여기 모아 두어 화면 테스트가 namespace를 잘못 쓰는
 * 실수를 한 곳에서 막는다.
 */

export const OPERATOR_REFRESH_PATH =
  '/api/v1/store-operators/auth/token-refreshes'
export const OPERATOR_SESSIONS_PATH = '/api/v1/store-operators/auth/sessions'
export const OPERATOR_SESSION_CURRENT_PATH =
  '/api/v1/store-operators/auth/sessions/current'
export const OPERATOR_CSRF_PATH =
  '/api/v1/store-operators/auth/csrf-tokens/current'
export const OPERATOR_ACCOUNTS_PATH = '/api/v1/store-operators/auth/accounts'
export const OPERATOR_STORES_PATH = '/api/v1/store-operators/stores'
export const STORE_CATEGORIES_PATH = '/api/v1/store-categories'
export const STORE_TAGS_PATH = '/api/v1/store-tags'
export const MENU_CATEGORIES_PATH = '/api/v1/menu-categories'

export const STORE_ID = '7'

export function operatorStorePath(suffix = ''): string {
  return `${OPERATOR_STORES_PATH}/${STORE_ID}${suffix}`
}

export function tokenData(accessToken = 'operator-access-token') {
  return { accessToken, tokenType: 'Bearer', expiresIn: 3600 }
}

/** 비로그인 상태. 세션 복구 실패가 정상 경로다. */
export const unauthenticatedOperator = http.post(OPERATOR_REFRESH_PATH, () =>
  errorResponse(
    401,
    AuthErrorCode.REFRESH_TOKEN_REQUIRED,
    'Refresh Token 쿠키가 필요합니다.',
  ),
)

export function authenticatedOperator(accessToken?: string) {
  return http.post(OPERATOR_REFRESH_PATH, () =>
    successResponse(tokenData(accessToken)),
  )
}

export function managedStore(overrides: Partial<ManagedStore> = {}): ManagedStore {
  return {
    storeId: STORE_ID,
    name: '카페 에비뉴',
    region: 'SEOUL',
    address: '서울 강남구 테헤란로 152',
    timeZoneId: 'Asia/Seoul',
    storeCategoryCode: 'CAFE_DESSERT',
    verificationStatus: 'APPROVED',
    operationStatus: 'OPEN',
    modes: {
      reservationEnabled: true,
      menuHoldEnabled: false,
      pickupEnabled: true,
    },
    /*
     * 좌표 검증 상태는 서버가 주소 버전마다 계산해 내려준다.
     * 기본 fixture는 아직 검증되지 않은 상태로 둔다. 좌표를 지어내지 않는다.
     */
    geocoding: {
      status: 'UNVERIFIED',
      latitude: null,
      longitude: null,
      verifiedAddress: null,
      verifiedAt: null,
      addressVersion: 1,
    },
    ...overrides,
  }
}

export const managedStoreHandler = http.get(operatorStorePath(), () =>
  successResponse(managedStore()),
)

export function catalogHandlers() {
  return [
    http.get(STORE_CATEGORIES_PATH, () =>
      successResponse({
        items: [
          { code: 'CAFE_DESSERT', displayName: '카페·디저트' },
          { code: 'KOREAN', displayName: '한식' },
        ],
      }),
    ),
    http.get(STORE_TAGS_PATH, () =>
      successResponse({
        items: [
          { code: 'SPECIALTY', displayName: '스페셜티' },
          { code: 'PARKING', displayName: '주차 가능' },
        ],
      }),
    ),
    http.get(MENU_CATEGORIES_PATH, () =>
      successResponse({
        items: [
          { code: 'COFFEE', displayName: '커피' },
          { code: 'DESSERT', displayName: '디저트' },
        ],
      }),
    ),
  ]
}

export function menuVersion(overrides: Partial<MenuVersion> = {}): MenuVersion {
  return {
    versionNumber: 1,
    status: 'DRAFT',
    name: '에스프레소',
    description: '진한 한 잔',
    price: 4500,
    representative: false,
    primaryCategoryCode: 'COFFEE',
    secondaryCategoryCodes: [],
    localTags: [],
    holdSelectionAllowed: false,
    pickupSelectionAllowed: true,
    allergenInformationStatus: 'NOT_REGISTERED',
    allergenDisclosures: [],
    originInformationStatus: 'NOT_APPLICABLE',
    originDisclosures: [],
    alcoholic: false,
    ...overrides,
  }
}

export function managedMenu(overrides: Partial<ManagedMenu> = {}): ManagedMenu {
  return {
    menuId: '11',
    storeId: STORE_ID,
    visibility: 'VISIBLE',
    sellingStatus: 'SELLING',
    retired: false,
    draft: menuVersion(),
    ...overrides,
  }
}
