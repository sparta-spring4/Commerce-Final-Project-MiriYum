import { fireEvent, screen, waitFor } from '@testing-library/react'
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
} from '../../../store/store-operator/test/handlers'
import { renderOperator } from '../../../store/store-operator/test/renderOperator'
import { MenuInventoryPage } from './MenuInventoryPage'

const INVENTORY_PATH = operatorStorePath('/menu-inventory-buckets')
const MENUS_PATH = operatorStorePath('/menus')

const bucket = {
  inventoryBucketId: '301',
  menuId: '11',
  serviceDate: '2026-08-21',
  startTime: '11:00:00',
  endDate: '2026-08-21',
  endTime: '14:00:00',
  policyVersion: 2,
  totalSupply: 20,
  pools: { onlineHold: 10, onsite: 5, shared: 5 },
  sharedOnlineAllowed: true,
  availableOnlineQuantity: 12,
  availabilityStatus: 'AVAILABLE' as const,
}

function renderPage() {
  return renderOperator(<MenuInventoryPage />, {
    route: fillPath(STORE_OPERATOR_PATHS.menuInventory, { storeId: STORE_ID }),
    path: STORE_OPERATOR_PATHS.menuInventory,
  })
}

describe('메뉴 재고 화면', () => {
  it('메뉴명·제공 구간·풀 배분·온라인 잔여를 표시한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(MENUS_PATH, () => successResponse([
        managedMenu({ published: menuVersion({ status: 'PUBLISHED', name: '아메리카노' }) }),
      ])),
      http.get(INVENTORY_PATH, () => successResponse({
        items: [bucket],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
      })),
    )

    renderPage()

    expect((await screen.findAllByText('아메리카노')).length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('온라인 10 · 현장 5 · 공유 5')).toBeInTheDocument()
    expect(screen.getByText('온라인 사용 가능 12')).toBeInTheDocument()
    expect(screen.getByText('v2')).toBeInTheDocument()
  })

  it('합계가 맞지 않는 신규 버킷을 보내지 않고 수정 후 전체 필드를 전송한다', async () => {
    let requestBody: unknown
    server.use(
      authenticatedOperator(),
      http.get(MENUS_PATH, () => successResponse([
        managedMenu({ published: menuVersion({ status: 'PUBLISHED', name: '아메리카노' }) }),
      ])),
      http.get(INVENTORY_PATH, () => successResponse({
        items: [],
        page: { number: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
      })),
      http.post(INVENTORY_PATH, async ({ request }) => {
        requestBody = await request.json()
        return successResponse(bucket)
      }),
    )

    renderPage()
    await screen.findByText('등록된 재고 버킷이 없습니다.')
    fireEvent.click(screen.getByRole('button', { name: '새 재고 버킷' }))
    fireEvent.change(screen.getByLabelText('메뉴'), { target: { value: '11' } })
    fireEvent.change(screen.getByLabelText('제공 시작 날짜'), { target: { value: '2026-08-21' } })
    fireEvent.change(screen.getByLabelText('제공 시작 시각'), { target: { value: '11:00' } })
    fireEvent.change(screen.getByLabelText('제공 종료 날짜'), { target: { value: '2026-08-21' } })
    fireEvent.change(screen.getByLabelText('제공 종료 시각'), { target: { value: '14:00' } })
    fireEvent.change(screen.getByLabelText('총 공급'), { target: { value: '20' } })
    fireEvent.change(screen.getByLabelText('온라인 홀드'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('현장'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('공유'), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: '재고 버킷 저장' }))

    expect(await screen.findByText('세 풀의 합계가 총 공급과 같아야 합니다.')).toBeInTheDocument()
    expect(requestBody).toBeUndefined()

    fireEvent.change(screen.getByLabelText('공유'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('checkbox', { name: '공유 수량을 온라인에서도 사용' }))
    fireEvent.click(screen.getByRole('button', { name: '재고 버킷 저장' }))

    await waitFor(() => expect(requestBody).toEqual({
      menuId: '11',
      serviceDate: '2026-08-21',
      startTime: '11:00:00',
      endDate: '2026-08-21',
      endTime: '14:00:00',
      totalSupply: 20,
      pools: { onlineHold: 10, onsite: 5, shared: 5 },
      sharedOnlineAllowed: true,
      availabilityStatus: 'AVAILABLE',
    }))
  })

  it('기존 버킷 수정은 제공 구간을 바꾸지 않고 전체 수량 정책을 전송한다', async () => {
    let requestBody: unknown
    let currentBucket = bucket
    server.use(
      authenticatedOperator(),
      http.get(MENUS_PATH, () => successResponse([
        managedMenu({ published: menuVersion({ status: 'PUBLISHED', name: '아메리카노' }) }),
      ])),
      http.get(INVENTORY_PATH, () => successResponse({
        items: [currentBucket],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
      })),
      http.patch(`${INVENTORY_PATH}/301`, async ({ request }) => {
        requestBody = await request.json()
        currentBucket = {
          ...bucket,
          policyVersion: 3,
          totalSupply: 21,
          pools: { onlineHold: 11, onsite: 5, shared: 5 },
        }
        return successResponse(currentBucket)
      }),
    )

    renderPage()
    await screen.findAllByText('아메리카노')
    fireEvent.click(screen.getByRole('button', { name: '재고 301 수정' }))
    fireEvent.change(screen.getByLabelText('총 공급'), { target: { value: '21' } })
    fireEvent.change(screen.getByLabelText('온라인 홀드'), { target: { value: '11' } })
    fireEvent.click(screen.getByRole('button', { name: '재고 변경 저장' }))

    await waitFor(() => expect(requestBody).toEqual({
      totalSupply: 21,
      pools: { onlineHold: 11, onsite: 5, shared: 5 },
      sharedOnlineAllowed: true,
      availabilityStatus: 'AVAILABLE',
    }))
    expect(await screen.findByText('v3')).toBeInTheDocument()
  })
})
