import { render, screen } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import { operatorAccountDetail } from '../test/operatorFixtures'
import { OperatorDetailPage } from './OperatorDetailPage'

const DETAIL_PATH = '/api/v1/platform-operators/accounts/op-1001'

function renderDetail() {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <MemoryRouter initialEntries={['/admin/operators/op-1001']}>
          <Routes>
            <Route
              path="/admin/operators/:operatorId"
              element={<OperatorDetailPage />}
            />
          </Routes>
        </MemoryRouter>
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
}

describe('운영자 계정 상세 권한', () => {
  beforeEach(() => {
    server.use(
      authenticatedPlatformOperator(),
      http.get(DETAIL_PATH, () => successResponse(operatorAccountDetail())),
    )
  })

  it('중지 권한이 없으면 계정 중지 폼을 보여 주지 않는다', async () => {
    server.use(
      currentPlatformOperator({
        permissions: ['OPERATOR_AUTHORITY_MANAGE'],
      }),
    )
    renderDetail()

    await screen.findByText('op***@miriyum.hq')
    expect(screen.queryByText('계정 중지')).not.toBeInTheDocument()
  })

  it('중지 권한이 있으면 계정 중지 폼을 보여 준다', async () => {
    server.use(
      currentPlatformOperator({
        permissions: ['OPERATOR_AUTHORITY_MANAGE', 'OPERATOR_SUSPEND'],
      }),
    )
    renderDetail()

    expect(
      await screen.findByRole('heading', { name: '계정 중지' }),
    ).toBeInTheDocument()
  })
})
