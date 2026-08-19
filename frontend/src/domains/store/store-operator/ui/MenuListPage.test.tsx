import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { fireEvent, screen } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  catalogHandlers,
  managedMenu,
  menuVersion,
  operatorStorePath,
} from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { MenuListPage } from './MenuListPage'

const MENUS_PATH = operatorStorePath('/menus')

function renderList() {
  return renderOperator(<MenuListPage />, {
    route: fillPath(STORE_OPERATOR_PATHS.menus, { storeId: STORE_ID }),
    path: STORE_OPERATOR_PATHS.menus,
  })
}

describe('메뉴 목록 화면', () => {
  it('버전·노출·판매 상태를 각각 표시한다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () =>
        successResponse([
          managedMenu({
            draft: null,
            published: menuVersion({ status: 'PUBLISHED', name: '아메리카노' }),
            visibility: 'HIDDEN',
            sellingStatus: 'SOLD_OUT',
          }),
        ]),
      ),
    )

    renderList()

    expect(await screen.findByText('아메리카노')).toBeInTheDocument()
    expect(screen.getByText('게시됨 v1')).toBeInTheDocument()
    expect(screen.getByText('비공개')).toBeInTheDocument()
    expect(screen.getByText('품절')).toBeInTheDocument()
  })

  it('한 메뉴가 여러 버전 슬롯을 가지면 모두 보여 준다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () =>
        successResponse([
          managedMenu({
            draft: menuVersion({ versionNumber: 3, status: 'DRAFT' }),
            published: menuVersion({ versionNumber: 1, status: 'PUBLISHED' }),
          }),
        ]),
      ),
    )

    renderList()

    expect(await screen.findByText('게시됨 v1')).toBeInTheDocument()
    expect(screen.getByText('초안 v3')).toBeInTheDocument()
  })

  it('상태 필터로 목록을 좁힌다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () =>
        successResponse([
          managedMenu({
            menuId: '11',
            draft: menuVersion({ name: '초안 메뉴' }),
          }),
          managedMenu({
            menuId: '12',
            draft: null,
            published: menuVersion({ status: 'PUBLISHED', name: '게시 메뉴' }),
          }),
        ]),
      ),
    )

    renderList()
    await screen.findByText('초안 메뉴')

    fireEvent.click(screen.getByRole('button', { name: '게시' }))

    expect(screen.getByText('게시 메뉴')).toBeInTheDocument()
    expect(screen.queryByText('초안 메뉴')).not.toBeInTheDocument()
  })

  it('메뉴명 검색으로 목록을 좁힌다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () =>
        successResponse([
          managedMenu({ menuId: '21', draft: menuVersion({ name: '아메리카노' }) }),
          managedMenu({ menuId: '22', draft: menuVersion({ name: '카페라떼' }) }),
        ]),
      ),
    )

    renderList()
    await screen.findByText('아메리카노')

    fireEvent.change(screen.getByLabelText('메뉴 검색'), {
      target: { value: '라떼' },
    })

    expect(screen.getByText('카페라떼')).toBeInTheDocument()
    expect(screen.queryByText('아메리카노')).not.toBeInTheDocument()
  })

  it('검색은 서버에 다시 묻지 않고 받아 온 목록만 좁힌다', async () => {
    let requests = 0
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () => {
        requests += 1
        return successResponse([
          managedMenu({ menuId: '31', draft: menuVersion({ name: '아메리카노' }) }),
        ])
      }),
    )

    renderList()
    await screen.findByText('아메리카노')
    const initial = requests

    // 계약의 목록 조회에는 검색 파라미터가 없다. 없는 질의를 보내지 않는다.
    fireEvent.change(screen.getByLabelText('메뉴 검색'), {
      target: { value: '아메' },
    })

    expect(screen.getByText('아메리카노')).toBeInTheDocument()
    expect(requests).toBe(initial)
  })

  it('상태 필터와 검색을 함께 걸면 둘 다 만족하는 메뉴만 남는다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () =>
        successResponse([
          managedMenu({
            menuId: '41',
            draft: null,
            published: menuVersion({ status: 'PUBLISHED', name: '게시 라떼' }),
          }),
          managedMenu({ menuId: '42', draft: menuVersion({ name: '초안 라떼' }) }),
        ]),
      ),
    )

    renderList()
    await screen.findByText('초안 라떼')

    fireEvent.change(screen.getByLabelText('메뉴 검색'), {
      target: { value: '라떼' },
    })
    fireEvent.click(screen.getByRole('button', { name: '게시' }))

    expect(screen.getByText('게시 라떼')).toBeInTheDocument()
    expect(screen.queryByText('초안 라떼')).not.toBeInTheDocument()
  })

  it('검색 결과가 비면 목록이 비었을 때와 구분해 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () => successResponse([managedMenu()])),
    )

    renderList()
    await screen.findByText('에스프레소')

    fireEvent.change(screen.getByLabelText('메뉴 검색'), {
      target: { value: '없는메뉴' },
    })

    expect(screen.getByText('이 상태의 메뉴가 없습니다.')).toBeInTheDocument()
    expect(screen.queryByText('등록한 메뉴가 없습니다.')).not.toBeInTheDocument()
  })

  it('메뉴가 없으면 빈 상태를 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () => successResponse([])),
    )

    renderList()

    expect(await screen.findByText('등록한 메뉴가 없습니다.')).toBeInTheDocument()
  })

  it('필터 결과만 비면 빈 결과와 구분해 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () => successResponse([managedMenu()])),
    )

    renderList()
    await screen.findByText('에스프레소')

    fireEvent.click(screen.getByRole('button', { name: '운영 종료' }))

    expect(screen.getByText('이 상태의 메뉴가 없습니다.')).toBeInTheDocument()
  })

  it('조회 실패는 재시도 경로와 함께 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () =>
        errorResponse(503, 'COMMON_012', '일시적으로 이용할 수 없습니다.'),
      ),
    )

    renderList()

    expect(
      await screen.findByText(
        '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('카테고리 표시명은 서버 catalog에서 가져온다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(MENUS_PATH, () => successResponse([managedMenu()])),
    )

    renderList()

    // code(COFFEE)를 그대로 노출하거나 화면이 이름을 지어내지 않는다.
    expect(await screen.findByText('커피')).toBeInTheDocument()
    expect(screen.queryByText('COFFEE')).not.toBeInTheDocument()
  })
})
