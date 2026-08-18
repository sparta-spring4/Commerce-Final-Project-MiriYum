import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, test } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import type { SupportCase } from '../api/memberSupportApi'
import { SupportCaseDetailPage } from './SupportCaseDetailPage'

const DETAIL_PATH =
  '/api/v1/platform-operators/member-support-cases/case-1001'
const DECISION_PATH = `${DETAIL_PATH}/decisions`
const REAUTH_PATH = '/api/v1/platform-operators/reauthentication-approvals'

function supportCase(caseType: SupportCase['caseType']): SupportCase {
  return {
    caseId: 'case-1001',
    caseType,
    accountType: 'CONSUMER',
    accountId: 'member-1001',
    status: 'ASSIGNED',
    version: 4,
    assignedOperatorId: 'operator-1001',
    submittedAt: '2026-08-18T10:00:00+09:00',
  }
}

function renderPage() {
  render(
    <TestQueryProvider>
      <MemoryRouter initialEntries={['/admin/member-support-cases/case-1001']}>
        <PlatformOperatorAuthProvider>
          <Routes>
            <Route
              path="/admin/member-support-cases/:caseId"
              element={<SupportCaseDetailPage />}
            />
          </Routes>
        </PlatformOperatorAuthProvider>
      </MemoryRouter>
    </TestQueryProvider>,
  )
}

describe('회원지원 사건 결정', () => {
  beforeEach(() => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({
        permissions: ['MEMBER_RECOVERY', 'ACCOUNT_APPEAL_REVIEW'],
      }),
    )
  })

  test('계정 복구 사건에는 서버가 허용하는 승인과 반려만 노출한다', async () => {
    server.use(http.get(DETAIL_PATH, () => successResponse(supportCase('ACCOUNT_RECOVERY'))))
    renderPage()

    const decision = await screen.findByLabelText('결정')
    expect(within(decision).getByRole('option', { name: '승인' })).toBeInTheDocument()
    expect(within(decision).getByRole('option', { name: '반려' })).toBeInTheDocument()
    expect(within(decision).queryByRole('option', { name: '취소' })).not.toBeInTheDocument()
  })

  test('제재 경감은 선택한 수준과 제한 기능을 결정 요청에 보낸다', async () => {
    let decisionBody: unknown = null
    server.use(
      http.get(DETAIL_PATH, () => successResponse(supportCase('ACCOUNT_APPEAL'))),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1001',
          expiresAt: '2026-08-18T10:05:00+09:00',
        }),
      ),
      http.post(DECISION_PATH, async ({ request }) => {
        decisionBody = await request.json()
        return successResponse(null)
      }),
    )
    renderPage()

    fireEvent.change(await screen.findByLabelText('결정'), {
      target: { value: 'REDUCE' },
    })
    fireEvent.change(screen.getByLabelText('경감 수준'), {
      target: { value: 'FEATURE_RESTRICTION' },
    })
    fireEvent.click(screen.getByLabelText('예약'))
    fireEvent.change(screen.getByLabelText('사유 코드'), {
      target: { value: 'APPEAL_ACCEPTED' },
    })
    fireEvent.click(screen.getByRole('button', { name: '결정 기록' }))

    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() =>
      expect(decisionBody).toEqual({
        decision: 'REDUCE',
        reasonCode: 'APPEAL_ACCEPTED',
        reducedLevel: 'FEATURE_RESTRICTION',
        restrictedFeatures: ['RESERVATION'],
      }),
    )
  })
})
