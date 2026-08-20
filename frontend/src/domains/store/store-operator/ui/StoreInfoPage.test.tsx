import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  catalogHandlers,
  managedStore,
  managedStoreHandler,
  operatorStorePath,
} from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { StoreInfoPage } from './StoreInfoPage'

function renderStoreInfo() {
  server.use(http.get(operatorStorePath('/images'), () => successResponse([])))
  return renderOperator(<StoreInfoPage />, {
    route: fillPath(STORE_OPERATOR_PATHS.store, { storeId: STORE_ID }),
    path: STORE_OPERATOR_PATHS.store,
  })
}

function save() {
  fireEvent.click(screen.getByRole('button', { name: '변경 사항 저장' }))
}

describe('매장 정보 화면', () => {
  it('조회한 값으로 폼을 채운다', async () => {
    server.use(authenticatedOperator(), ...catalogHandlers(), managedStoreHandler)

    renderStoreInfo()

    expect(await screen.findByLabelText('매장명')).toHaveValue('카페 에비뉴')
    expect(screen.getByLabelText('매장 주소')).toHaveValue(
      '서울 강남구 테헤란로 152',
    )
    expect(screen.getByText('매장 이미지')).toBeInTheDocument()
  })

  it('건드리지 않은 필드는 PATCH 본문에 넣지 않는다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      managedStoreHandler,
      http.patch(operatorStorePath(), async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(managedStore({ name: '카페 에비뉴 본점' }))
      }),
    )

    renderStoreInfo()
    fireEvent.change(await screen.findByLabelText('매장명'), {
      target: { value: '카페 에비뉴 본점' },
    })
    save()

    await waitFor(() => expect(body).not.toBeNull())
    // 조회 응답에 없는 소개·태그를 빈 값으로 함께 보내면 서버의 기존 값이 지워진다.
    expect(body).toEqual({ name: '카페 에비뉴 본점' })
  })

  it('변경한 항목이 없으면 요청을 보내지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      managedStoreHandler,
      http.patch(operatorStorePath(), () => {
        called = true
        return successResponse(managedStore())
      }),
    )

    renderStoreInfo()
    await screen.findByLabelText('매장명')
    save()

    expect(await screen.findByText('변경한 항목이 없습니다.')).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('운영 상태는 편집 가능한 두 값만 제공한다', async () => {
    server.use(authenticatedOperator(), ...catalogHandlers(), managedStoreHandler)

    renderStoreInfo()
    const select = await screen.findByLabelText('운영 상태')

    const values = Array.from(
      select.querySelectorAll('option'),
      (option) => option.value,
    )
    // 폐업(CLOSED)은 계약의 편집 가능 값이 아니다.
    expect(values).toEqual(['OPEN', 'TEMPORARILY_CLOSED'])
  })

  it('조회 계약이 없는 필드는 대체된다는 사실을 알린다', async () => {
    server.use(authenticatedOperator(), ...catalogHandlers(), managedStoreHandler)

    renderStoreInfo()

    expect(
      await screen.findByText(
        '현재 값을 조회하는 계약이 없습니다. 입력해 저장하면 기존 소개를 대체합니다.',
      ),
    ).toBeInTheDocument()
  })

  it('접근 권한이 없으면 다른 사용자 정보를 노출하지 않고 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.get(operatorStorePath(), () =>
        errorResponse(403, 'STORE_003', '대상 매장의 대표 운영자가 아닙니다.'),
      ),
    )

    renderStoreInfo()

    expect(
      await screen.findByText('이 매장의 대표 운영자가 아닙니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText('매장명')).not.toBeInTheDocument()
  })

  it('저장 충돌은 최신 상태를 다시 확인하도록 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      managedStoreHandler,
      http.patch(operatorStorePath(), () =>
        errorResponse(409, 'STORE_005', '현재 매장 상태에서 처리할 수 없습니다.'),
      ),
    )

    renderStoreInfo()
    fireEvent.change(await screen.findByLabelText('매장명'), {
      target: { value: '새 이름' },
    })
    save()

    expect(
      await screen.findByText('현재 매장 상태에서는 이 작업을 할 수 없습니다.'),
    ).toBeInTheDocument()
  })
})
