import { http } from 'msw'
import { successResponse } from '../../../test/msw/envelope'
import { MENU_CATEGORIES, STORE_CATEGORIES, STORE_TAGS } from './fixtures'

/**
 * 화면이 항상 부르는 catalog 조회. 각 테스트가 매번 다시 쓰지 않도록 모아 둔다.
 * setup의 onUnhandledRequest: 'error'가 등록하지 않은 호출을 실패로 잡는다.
 */
export const catalogHandlers = [
  http.get('/api/v1/store-categories', () =>
    successResponse({ items: STORE_CATEGORIES }),
  ),
  http.get('/api/v1/store-tags', () => successResponse({ items: STORE_TAGS })),
  http.get('/api/v1/menu-categories', () =>
    successResponse({ items: MENU_CATEGORIES }),
  ),
]
