import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
import { storeSanctionCaseDetail } from '../test/storeFixtures'
import { StoreSanctionCasePage } from './StoreSanctionCasePage'

const CASE_PATH =
  '/api/v1/platform-operators/stores/4001/sanction-cases/case-7001'
const ASSIGNMENT_PATH = `${CASE_PATH}/assignments`

function renderPage() {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <MemoryRouter
          initialEntries={['/admin/stores/4001/sanction-cases/case-7001']}
        >
          <Routes>
            <Route
              path="/admin/stores/:storeId/sanction-cases/:caseId"
              element={<StoreSanctionCasePage />}
            />
          </Routes>
        </MemoryRouter>
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
}

describe('매장 제재 사건 상세', () => {
  beforeEach(() => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({
        permissions: ['STORE_READ_MINIMAL', 'STORE_SANCTION'],
      }),
    )
  })

  it('자기 배정 성공 뒤 서버가 반환한 새 case version으로 다시 조회한다', async () => {
    const requestedVersions: string[] = []
    server.use(
      http.get(CASE_PATH, ({ request }) => {
        const version = request.headers.get('X-Admin-Case-Version') ?? ''
        requestedVersions.push(version)
        return successResponse(
          storeSanctionCaseDetail({
            caseVersion: Number(version),
            status: version === '2' ? 'ASSIGNED' : 'SUBMITTED',
            assignedOperatorId: version === '2' ? 1001 : null,
          }),
        )
      }),
      http.post(ASSIGNMENT_PATH, () =>
        successResponse({
          caseId: 'case-7001',
          storeId: 4001,
          violationType: 'NO_SHOW_ABUSE',
          evidenceReferences: ['audit-9001'],
          policyVersion: 'POLICY_2026_02',
          status: 'ASSIGNED',
          caseVersion: 2,
          createdBy: 9001,
          assignedOperatorId: 1001,
          createdAt: '2026-08-16T01:00:00Z',
        }),
      ),
    )
    renderPage()

    fireEvent.change(await screen.findByLabelText('사건 version'), {
      target: { value: '1' },
    })
    fireEvent.click(screen.getByRole('button', { name: '조회 시작' }))
    fireEvent.click(
      await screen.findByRole('button', { name: '나에게 배정' }),
    )

    await waitFor(() => expect(requestedVersions).toHaveLength(2))
    expect(requestedVersions).toEqual(['1', '2'])
  })
})
