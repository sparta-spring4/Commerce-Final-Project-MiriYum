import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import { adminStorePage, adminStoreSummary } from '../test/storeFixtures'
import { StoreListPage } from './StoreListPage'

const STORES_PATH = '/api/v1/platform-operators/stores'

function renderList() {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <MemoryRouter initialEntries={['/admin/stores']}>
          <StoreListPage />
        </MemoryRouter>
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
}

function startQuery() {
  fireEvent.click(screen.getByRole('button', { name: '조회 시작' }))
}

describe('매장 목록', () => {
  beforeEach(() => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['STORE_READ_MINIMAL'] }),
    )
  })

  it('사유를 고르기 전에는 매장 조회를 보내지 않는다', async () => {
    let requested = 0
    server.use(
      http.get(STORES_PATH, () => {
        requested += 1
        return successResponse(adminStorePage())
      }),
    )
    renderList()

    expect(await screen.findByRole('combobox', { name: '조회 사유' })).toBeInTheDocument()
    // 게이트가 떠 있는 동안은 요청이 0이어야 한다.
    expect(requested).toBe(0)

    startQuery()
    await waitFor(() => expect(requested).toBe(1))
  })

  it('선택한 사유를 X-Admin-Reason-Code 헤더로 보낸다', async () => {
    let reasonHeader: string | null = null
    server.use(
      http.get(STORES_PATH, ({ request }) => {
        reasonHeader = request.headers.get('X-Admin-Reason-Code')
        return successResponse(adminStorePage())
      }),
    )
    renderList()
    await screen.findByRole('combobox', { name: '조회 사유' })

    fireEvent.change(screen.getByRole('combobox', { name: '조회 사유' }), {
      target: { value: 'STORE_ENFORCEMENT' },
    })
    startQuery()

    await screen.findByText('미리얌 강남점')
    expect(reasonHeader).toBe('STORE_ENFORCEMENT')
  })

  it('계약이 주는 필드만 표시한다', async () => {
    server.use(
      http.get(STORES_PATH, () =>
        successResponse(
          adminStorePage({
            content: [
              adminStoreSummary({
                pickupEnabled: false,
                menuHoldEnabled: false,
                activeSanctionTypes: ['TEMPORARY_SUSPENSION'],
              }),
            ],
          }),
        ),
      ),
    )
    renderList()
    await screen.findByRole('combobox', { name: '조회 사유' })
    startQuery()

    expect(await screen.findByText('미리얌 강남점')).toBeInTheDocument()
    // 상태 필터 option에도 같은 문구가 있으므로 표 안으로 좁혀서 확인한다.
    const table = within(screen.getByRole('table'))
    expect(table.getByText('9001')).toBeInTheDocument()
    expect(table.getByText('운영 중')).toBeInTheDocument()
    // 기능 활성 여부는 색이 아니라 문구로 전한다.
    expect(table.getByText('예약')).toBeInTheDocument()
    expect(table.getByText('기간 정지')).toBeInTheDocument()

    // 시안에 있으나 계약에 없는 항목은 화면에 두지 않는다.
    expect(screen.queryByText('점주명')).not.toBeInTheDocument()
    expect(screen.queryByText('업종')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '데이터 내보내기' }),
    ).not.toBeInTheDocument()
  })

  it('검색어는 제출할 때만 조회한다', async () => {
    const keywords: (string | null)[] = []
    server.use(
      http.get(STORES_PATH, ({ request }) => {
        keywords.push(new URL(request.url).searchParams.get('keyword'))
        return successResponse(adminStorePage())
      }),
    )
    renderList()
    await screen.findByRole('combobox', { name: '조회 사유' })
    startQuery()
    await screen.findByText('미리얌 강남점')

    fireEvent.change(screen.getByRole('textbox', { name: '검색' }), {
      target: { value: '강남' },
    })
    // 타이핑만으로는 요청이 늘지 않는다. 조회마다 감사 기록이 남기 때문이다.
    expect(keywords).toHaveLength(1)

    fireEvent.click(screen.getByRole('button', { name: '검색' }))
    await waitFor(() => expect(keywords).toHaveLength(2))
    expect(keywords[1]).toBe('강남')
  })

  it('매장 조회 권한이 없으면 조회하지 않고 안내한다', async () => {
    let requested = 0
    server.use(
      currentPlatformOperator({ permissions: ['MEMBER_READ_MINIMAL'] }),
      http.get(STORES_PATH, () => {
        requested += 1
        return successResponse(adminStorePage())
      }),
    )
    renderList()

    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(requested).toBe(0)
  })
})
