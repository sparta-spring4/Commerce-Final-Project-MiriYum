import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
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
        permissions: [
          'MEMBER_READ_MINIMAL',
          'MEMBER_RECOVERY',
          'ACCOUNT_APPEAL_REVIEW',
        ],
      }),
    )
  })

  test.each([
    ['ACCOUNT_RECOVERY', 'MEMBER_RECOVERY'],
    ['ACCOUNT_APPEAL', 'ACCOUNT_APPEAL_REVIEW'],
  ] as const)(
    '%s 사건의 명령 권한이 없으면 배정과 결정 UI를 노출하지 않는다',
    async (caseType, requiredPermission) => {
      server.use(
        currentPlatformOperator({ permissions: ['MEMBER_READ_MINIMAL'] }),
        http.get(DETAIL_PATH, () => successResponse(supportCase(caseType))),
      )
      renderPage()

      expect(await screen.findByText('case-1001')).toBeInTheDocument()
      expect(
        screen.queryByRole('button', { name: '나에게 배정' }),
      ).not.toBeInTheDocument()
      expect(
        screen.queryByRole('form', { name: '사건 결정' }),
      ).not.toBeInTheDocument()
      expect(screen.getByText(requiredPermission)).toBeInTheDocument()
    },
  )

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

  test('재인증 중 결정 입력이 바뀌면 이전 멱등 시도로 명령을 보내지 않는다', async () => {
    let decisionRequests = 0
    server.use(
      http.get(DETAIL_PATH, () =>
        successResponse(supportCase('ACCOUNT_APPEAL')),
      ),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1001',
          expiresAt: '2026-08-18T10:05:00+09:00',
        }),
      ),
      http.post(DECISION_PATH, () => {
        decisionRequests += 1
        return HttpResponse.error()
      }),
    )
    renderPage()

    const reasonCode = await screen.findByLabelText('사유 코드')
    fireEvent.change(reasonCode, { target: { value: 'ORIGINAL_REASON' } })
    fireEvent.click(screen.getByRole('button', { name: '결정 기록' }))
    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await screen.findByText(/서버에 연결하지 못했습니다/)
    expect(decisionRequests).toBe(1)

    fireEvent.click(screen.getByRole('button', { name: '결정 기록' }))
    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    expect(screen.getByRole('form', { name: '사건 결정' })).toHaveAttribute(
      'inert',
    )

    // jsdom에서는 inert의 사용자 입력 차단을 구현하지 않으므로 강제로 변경해
    // 승인 경계의 revision 검사가 별도로 동작하는지 검증한다.
    fireEvent.change(reasonCode, { target: { value: 'CHANGED_REASON' } })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await screen.findByText(/재인증 중 명령 입력이 변경됐습니다/)
    expect(decisionRequests).toBe(1)
    expect(screen.getByRole('form', { name: '사건 결정' })).not.toHaveAttribute(
      'inert',
    )
  })
})
