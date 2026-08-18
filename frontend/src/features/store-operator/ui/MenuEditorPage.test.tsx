import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { ROUTES, fillPath } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  catalogHandlers,
  managedMenu,
  managedStoreHandler,
  menuVersion,
  operatorStorePath,
} from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { MenuEditorPage } from './MenuEditorPage'

const MENU_ID = '11'
const MENUS_PATH = operatorStorePath('/menus')
const MENU_PATH = `${MENUS_PATH}/${MENU_ID}`

function renderCreatePage() {
  return renderOperator(<MenuEditorPage />, {
    route: fillPath(ROUTES.storeOperatorMenuCreate, { storeId: STORE_ID }),
    path: ROUTES.storeOperatorMenuCreate,
    probePaths: [ROUTES.storeOperatorMenu],
  })
}

function renderEditPage() {
  return renderOperator(<MenuEditorPage />, {
    route: fillPath(ROUTES.storeOperatorMenu, {
      storeId: STORE_ID,
      menuId: MENU_ID,
    }),
    path: ROUTES.storeOperatorMenu,
  })
}

function fillContent() {
  fireEvent.change(screen.getByLabelText('메뉴명'), {
    target: { value: '에스프레소' },
  })
  fireEvent.change(screen.getByLabelText('가격'), { target: { value: '4500' } })
  fireEvent.change(screen.getByLabelText('주 카테고리'), {
    target: { value: 'COFFEE' },
  })
}

function chooseDisclosures(allergen = 'NOT_REGISTERED', origin = 'NOT_APPLICABLE') {
  fireEvent.change(screen.getByLabelText('알레르기 정보 등록 여부'), {
    target: { value: allergen },
  })
  fireEvent.change(screen.getByLabelText('원산지 정보 등록 여부'), {
    target: { value: origin },
  })
}

describe('메뉴 편집 화면', () => {
  it('표시 정보를 고르지 않으면 저장하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.post(MENUS_PATH, () => {
        called = true
        return successResponse(managedMenu())
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('메뉴명')

    fillContent()
    fireEvent.click(screen.getByRole('button', { name: '메뉴 초안 만들기' }))

    expect(
      await screen.findByText('알레르기 표시 여부를 선택해 주세요.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('알레르기를 등록으로 두면 항목 없이 저장하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.post(MENUS_PATH, () => {
        called = true
        return successResponse(managedMenu())
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('메뉴명')

    fillContent()
    chooseDisclosures('REGISTERED')
    fireEvent.click(screen.getByRole('button', { name: '메뉴 초안 만들기' }))

    expect(
      await screen.findByText('등록으로 두려면 항목을 최소 한 개 추가해 주세요.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('계약이 요구하는 표시 필드를 모두 담아 보낸다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.post(MENUS_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(managedMenu())
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('메뉴명')

    fillContent()
    chooseDisclosures()
    fireEvent.click(screen.getByRole('button', { name: '메뉴 초안 만들기' }))

    await waitFor(() => expect(body).not.toBeNull())
    expect(body).toMatchObject({
      name: '에스프레소',
      price: 4500,
      allergenInformationStatus: 'NOT_REGISTERED',
      allergenDisclosures: [],
      originInformationStatus: 'NOT_APPLICABLE',
      originDisclosures: [],
      alcoholic: false,
    })
  })

  it('생성에 성공하면 해당 메뉴 상세로 이어 간다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.post(MENUS_PATH, () => successResponse(managedMenu())),
    )

    renderCreatePage()
    await screen.findByLabelText('메뉴명')

    fillContent()
    chooseDisclosures()
    fireEvent.click(screen.getByRole('button', { name: '메뉴 초안 만들기' }))

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        `/store-operator/stores/${STORE_ID}/menus/${MENU_ID}`,
      ),
    )
  })

  it('새 메뉴에는 게시·노출·판매 명령을 아직 열지 않는다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler, ...catalogHandlers())

    renderCreatePage()

    expect(
      await screen.findByText('먼저 초안을 만들어 주세요.'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText('노출')).not.toBeInTheDocument()
  })

  it('초안 저장은 게시가 아니라고 밝힌다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.get(MENU_PATH, () => successResponse(managedMenu())),
      http.put(MENU_PATH, () => successResponse(managedMenu())),
    )

    renderEditPage()
    await screen.findByLabelText('메뉴명')

    fireEvent.click(screen.getByRole('button', { name: '초안 저장' }))

    expect(await screen.findByText('초안을 저장했습니다.')).toBeInTheDocument()
    expect(
      screen.getByText(/아직 고객에게 보이지 않습니다/),
    ).toBeInTheDocument()
  })

  it('버전·노출·판매를 각각 다른 명령으로 다룬다', async () => {
    const calls: string[] = []
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.get(MENU_PATH, () => successResponse(managedMenu())),
      http.patch(`${MENU_PATH}/visibility`, async ({ request }) => {
        calls.push(`visibility:${JSON.stringify(await request.json())}`)
        return successResponse(managedMenu({ visibility: 'HIDDEN' }))
      }),
      http.patch(`${MENU_PATH}/selling-status`, async ({ request }) => {
        calls.push(`selling:${JSON.stringify(await request.json())}`)
        return successResponse(managedMenu({ sellingStatus: 'SOLD_OUT' }))
      }),
    )

    renderEditPage()

    fireEvent.change(await screen.findByLabelText('노출'), {
      target: { value: 'HIDDEN' },
    })
    fireEvent.change(screen.getByLabelText('노출 변경 사유'), {
      target: { value: '리뉴얼 준비' },
    })
    fireEvent.click(screen.getByRole('button', { name: '노출 상태 변경' }))
    await waitFor(() => expect(calls).toHaveLength(1))

    fireEvent.change(screen.getByLabelText('판매'), {
      target: { value: 'SOLD_OUT' },
    })
    fireEvent.change(screen.getByLabelText('판매 변경 사유'), {
      target: { value: '재료 소진' },
    })
    fireEvent.click(screen.getByRole('button', { name: '판매 상태 변경' }))
    await waitFor(() => expect(calls).toHaveLength(2))

    // 두 축은 독립이다. 한 요청으로 합치지 않는다.
    expect(calls[0]).toBe(
      'visibility:{"visibility":"HIDDEN","changeReason":"리뉴얼 준비"}',
    )
    expect(calls[1]).toBe(
      'selling:{"sellingStatus":"SOLD_OUT","changeReason":"재료 소진"}',
    )
  })

  it('변경 사유 없이 상태를 바꾸지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.get(MENU_PATH, () => successResponse(managedMenu())),
      http.patch(`${MENU_PATH}/visibility`, () => {
        called = true
        return successResponse(managedMenu())
      }),
    )

    renderEditPage()
    fireEvent.click(
      await screen.findByRole('button', { name: '노출 상태 변경' }),
    )

    expect(await screen.findByText('변경 사유를 입력해 주세요.')).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('메뉴 게시는 계약의 mode 필드로 보낸다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.get(MENU_PATH, () => successResponse(managedMenu())),
      http.post(`${MENU_PATH}/publications`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(
          managedMenu({
            draft: null,
            published: menuVersion({ status: 'PUBLISHED' }),
          }),
        )
      }),
    )

    renderEditPage()

    fireEvent.change(await screen.findByLabelText('변경 사유'), {
      target: { value: '신규 메뉴 공개' },
    })
    fireEvent.click(screen.getByRole('button', { name: '버전 1 게시' }))

    await waitFor(() => expect(body).not.toBeNull())
    // 스케줄 계약은 publicationMode, 메뉴 계약은 mode다. 이름이 다르다.
    expect(body).toEqual({ mode: 'IMMEDIATE', changeReason: '신규 메뉴 공개' })
  })

  it('운영 종료된 메뉴에는 상태 명령을 열지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.get(MENU_PATH, () =>
        successResponse(
          managedMenu({ retired: true, draft: null, published: menuVersion() }),
        ),
      ),
    )

    renderEditPage()

    expect(
      await screen.findByText('이 메뉴는 운영을 종료했습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '노출 상태 변경' }),
    ).not.toBeInTheDocument()
  })

  it('메뉴 상태 전이 충돌은 서버 코드로 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      ...catalogHandlers(),
      http.get(MENU_PATH, () => successResponse(managedMenu())),
      http.post(`${MENU_PATH}/publications`, () =>
        errorResponse(409, 'STORE_010', '현재 상태에서 전이할 수 없습니다.'),
      ),
    )

    renderEditPage()

    fireEvent.change(await screen.findByLabelText('변경 사유'), {
      target: { value: '공개' },
    })
    fireEvent.click(screen.getByRole('button', { name: '버전 1 게시' }))

    expect(
      await screen.findByText('현재 메뉴 상태에서는 이 전이를 할 수 없습니다.'),
    ).toBeInTheDocument()
  })
})
