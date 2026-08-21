import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  managedMenu,
  menuVersion,
  operatorStorePath,
} from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { RepresentativeMenusPage } from './RepresentativeMenusPage'

const REPRESENTATIVE_PATH = operatorStorePath('/representative-menus')
const MENUS_PATH = operatorStorePath('/menus')

function publishedMenu(menuId: string, name: string) {
  return managedMenu({
    menuId,
    draft: undefined,
    published: menuVersion({
      versionNumber: Number(menuId),
      status: 'PUBLISHED',
      name,
    }),
  })
}

function renderPage() {
  return renderOperator(<RepresentativeMenusPage />, {
    route: fillPath(STORE_OPERATOR_PATHS.representativeMenus, { storeId: STORE_ID }),
    path: STORE_OPERATOR_PATHS.representativeMenus,
  })
}

describe('대표 메뉴 관리 화면', () => {
  it('주의 상태와 선택 불가 사유를 표시한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(MENUS_PATH, () => successResponse([
        publishedMenu('11', '아메리카노'),
        managedMenu({
          menuId: '15',
          visibility: 'HIDDEN',
          draft: undefined,
          published: menuVersion({ status: 'PUBLISHED', name: '숨김 메뉴' }),
        }),
      ])),
      http.get(REPRESENTATIVE_PATH, () => successResponse({
        version: 4,
        status: 'REQUIRES_ATTENTION',
        items: [{ menuId: '11', displayOrder: 1, publishedVersionNumber: 11, name: '아메리카노', price: 4500, sellingStatus: 'SELLING' }],
      })),
    )

    renderPage()

    expect(await screen.findByText('대표 메뉴 구성을 확인해 주세요.')).toBeInTheDocument()
    const hiddenRow = screen.getByText('숨김 메뉴').closest('li')
    expect(hiddenRow).not.toBeNull()
    expect(within(hiddenRow!).getByRole('checkbox')).toBeDisabled()
    expect(within(hiddenRow!).getByText('비공개 메뉴')).toBeInTheDocument()
  })

  it('3~5개 검증 후 선택 순서와 현재 버전으로 전체 교체한다', async () => {
    let requestBody: unknown
    let idempotencyKey: string | null = null
    const menus = [
      publishedMenu('11', '아메리카노'),
      publishedMenu('12', '카페라떼'),
      publishedMenu('13', '바닐라라떼'),
      publishedMenu('14', '콜드브루'),
    ]
    server.use(
      authenticatedOperator(),
      http.get(MENUS_PATH, () => successResponse(menus)),
      http.get(REPRESENTATIVE_PATH, () => successResponse({
        version: 4,
        status: 'CONFIGURED',
        items: menus.slice(0, 3).map((menu, index) => ({
          menuId: menu.menuId,
          displayOrder: index + 1,
          publishedVersionNumber: Number(menu.menuId),
          name: menu.published!.name,
          price: menu.published!.price,
          sellingStatus: 'SELLING',
        })),
      })),
      http.put(REPRESENTATIVE_PATH, async ({ request }) => {
        requestBody = await request.json()
        idempotencyKey = request.headers.get('Idempotency-Key')
        return successResponse({
          version: 5,
          status: 'CONFIGURED',
          items: [],
        })
      }),
    )

    renderPage()
    await screen.findByText('현재 3개 선택')
    fireEvent.click(screen.getByRole('checkbox', { name: '바닐라라떼 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '대표 메뉴 저장' }))
    expect(await screen.findByText('대표 메뉴는 3개 이상 선택해 주세요.')).toBeInTheDocument()
    expect(requestBody).toBeUndefined()

    fireEvent.click(screen.getByRole('checkbox', { name: '바닐라라떼 선택' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '콜드브루 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '콜드브루 위로' }))
    fireEvent.click(screen.getByRole('button', { name: '대표 메뉴 저장' }))

    await waitFor(() => expect(requestBody).toEqual({
      expectedVersion: 4,
      menuIds: ['11', '12', '14', '13'],
    }))
    expect(idempotencyKey).toBeTruthy()
    expect(await screen.findByText('대표 메뉴를 저장했습니다.')).toBeInTheDocument()
  })
})
