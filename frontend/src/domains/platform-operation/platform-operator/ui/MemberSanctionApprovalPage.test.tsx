import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { QueryClient } from '@tanstack/react-query'
import { delay, http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { TestQueryProvider } from '../../../../test/TestQueryProvider'
import { AuthErrorCode } from '../../../../shared/auth/authErrors'
import { PlatformOperatorAuthProvider } from '../../../account/platform-operator/auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../../account/platform-operator/auth/test/handlers'
import { MemberSanctionApprovalPage } from './MemberSanctionApprovalPage'

const REAUTH_PATH = '/api/v1/platform-operators/reauthentication-approvals'
const PENDING_APPROVALS_PATH =
  '/api/v1/platform-operators/member-sanctions/pending-additional-approvals'
const APPROVAL_PATH =
  '/api/v1/platform-operators/member-sanctions/sanction-1/additional-approvals'

function renderPage(onQueryClientReady?: (queryClient: QueryClient) => void) {
  render(
    <TestQueryProvider onReady={onQueryClientReady}>
      <PlatformOperatorAuthProvider>
        <MemberSanctionApprovalPage />
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
}

function pendingSanction(
  overrides: Partial<{
    accountType: 'CONSUMER' | 'STORE_OPERATOR'
    accountId: string
  }> = {},
) {
  return {
    sanctionId: 'sanction-1',
    version: 4,
    accountType: overrides.accountType ?? 'CONSUMER',
    accountId: overrides.accountId ?? 'member-1',
    reasonCode: 'ABUSE_REPORT',
    policyVersion: 'SANCTION_POLICY_V1',
    proposedAt: '2026-08-17T09:00:00Z',
  }
}

function pendingPage(content = [pendingSanction()]) {
  return successResponse({
    content,
    page: {
      number: 0,
      size: 20,
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : 1,
      hasNext: false,
    },
  })
}

function approvedSanction(status: string) {
  return successResponse({
    sanctionId: 'sanction-1',
    accountType: 'CONSUMER',
    accountId: 'member-1',
    level: 'PERMANENT_SUSPENSION',
    status,
    policyVersion: 'SANCTION_POLICY_V1',
    version: 5,
    restrictedFeatures: [],
    proposedAt: '2026-08-17T09:00:00Z',
  })
}

/** 서버 목록 항목을 선택하고 승인 사유를 입력해 재인증을 연다. */
async function selectAndSubmit(reasonCode = 'POLICY_CONFIRMED') {
  fireEvent.click(
    await screen.findByRole('button', { name: '제재 sanction-1 선택' }),
  )
  if (reasonCode.length > 0) {
    fireEvent.change(screen.getByLabelText('승인 사유 코드'), {
      target: { value: reasonCode },
    })
  }
  fireEvent.click(screen.getByRole('button', { name: '영구 정지 승인' }))
}

async function approveInDialog() {
  fireEvent.change(await screen.findByLabelText('현재 비밀번호'), {
    target: { value: 'Miriyum1!' },
  })
  fireEvent.click(screen.getByRole('button', { name: '확인' }))
}

describe('영구 정지 추가 승인', () => {
  beforeEach(() => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({
        permissions: ['ACCOUNT_PERMANENT_SANCTION_APPROVE'],
        roles: ['SUPER_ADMIN'],
      }),
      http.get(PENDING_APPROVALS_PATH, () => pendingPage()),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-17T10:05:00Z',
        }),
      ),
    )
  })

  it('서버가 반환한 승인 대기 항목을 선택하고 수동 ID 입력을 허용하지 않는다', async () => {
    renderPage()

    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.getByText('sanction-1')).toBeInTheDocument()
    fireEvent.click(
      screen.getByRole('button', { name: '제재 sanction-1 선택' }),
    )

    expect(screen.getByText('선택한 제재')).toBeInTheDocument()
    expect(screen.queryByLabelText('제재 ID')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('제재 version')).not.toBeInTheDocument()
  })

  it('선택한 항목의 path와 version으로 승인하고 목록을 다시 조회한다', async () => {
    let pendingRequests = 0
    let approvalHeaders: Headers | null = null
    let approvalBody: unknown = null
    server.use(
      http.get(PENDING_APPROVALS_PATH, () => {
        pendingRequests += 1
        return pendingPage()
      }),
      http.post(APPROVAL_PATH, async ({ request }) => {
        approvalHeaders = request.headers
        approvalBody = await request.json()
        return approvedSanction('APPLIED')
      }),
    )
    renderPage()
    await selectAndSubmit()
    await approveInDialog()

    await waitFor(() => expect(approvalHeaders).not.toBeNull())
    const sent = approvalHeaders!
    expect(sent.get('X-Admin-Reauthentication')).toBe('approval-1')
    expect(sent.get('If-Match')).toBe('4')
    expect(sent.get('Idempotency-Key')).not.toBeNull()
    expect(approvalBody).toEqual({
      decision: 'APPROVE',
      reasonCode: 'POLICY_CONFIRMED',
    })
    await waitFor(() => expect(pendingRequests).toBe(2))
    expect(screen.queryByText('선택한 제재')).not.toBeInTheDocument()
    expect(
      await screen.findByText('제재 sanction-1가 APPLIED 상태가 됐습니다.'),
    ).toBeInTheDocument()
  })

  it('재인증을 목록이 준 제재 대상 유형과 계정 ID에 결속한다', async () => {
    let reauthenticationBody: unknown = null
    server.use(
      http.get(PENDING_APPROVALS_PATH, () =>
        pendingPage([
          pendingSanction({
            accountType: 'STORE_OPERATOR',
            accountId: '9001',
          }),
        ]),
      ),
      http.post(REAUTH_PATH, async ({ request }) => {
        reauthenticationBody = await request.json()
        return successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-17T10:05:00Z',
        })
      }),
      http.post(APPROVAL_PATH, () => approvedSanction('APPLIED')),
    )
    renderPage()
    await selectAndSubmit()
    await approveInDialog()

    await waitFor(() => expect(reauthenticationBody).not.toBeNull())
    expect(reauthenticationBody).toMatchObject({
      purpose: 'PERMANENT_ACCOUNT_SANCTION_APPROVAL',
      targetType: 'STORE_OPERATOR_ACCOUNT',
      targetId: '9001',
    })
  })

  it('승인 사유가 비어 있으면 재인증과 명령을 보내지 않는다', async () => {
    let requested = 0
    server.use(
      http.post(APPROVAL_PATH, () => {
        requested += 1
        return approvedSanction('APPLIED')
      }),
    )
    renderPage()
    await selectAndSubmit('')

    expect(
      await screen.findByText('승인 사유 코드를 입력해 주세요.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('dialog', { name: '재인증이 필요합니다' }),
    ).not.toBeInTheDocument()
    expect(requested).toBe(0)
  })

  it('제안자 본인 승인 거부를 사용자 언어로 전한다', async () => {
    server.use(
      http.post(APPROVAL_PATH, () =>
        errorResponse(403, 'AUTH_014', '본인이 제안한 제재입니다.'),
      ),
    )
    renderPage()
    await selectAndSubmit()
    await approveInDialog()

    expect(
      await screen.findByText(
        '승인 권한이 없거나 제안자 본인이라 승인할 수 없습니다.',
      ),
    ).toBeInTheDocument()
  })

  it('409이면 stale 선택을 폐기하고 목록을 다시 조회한다', async () => {
    let pendingRequests = 0
    server.use(
      http.get(PENDING_APPROVALS_PATH, () => {
        pendingRequests += 1
        return pendingPage()
      }),
      http.post(APPROVAL_PATH, () =>
        errorResponse(
          409,
          AuthErrorCode.PERMANENT_SANCTION_APPROVAL_CONFLICT,
          '이미 처리된 제재입니다.',
        ),
      ),
    )
    renderPage()
    await selectAndSubmit()
    await approveInDialog()

    expect(
      await screen.findByText(
        '이미 승인됐거나 종결된 제안입니다. 목록을 갱신했습니다.',
      ),
    ).toBeInTheDocument()
    await waitFor(() => expect(pendingRequests).toBe(2))
    expect(screen.queryByText('선택한 제재')).not.toBeInTheDocument()
  })

  it('조회 중과 빈 목록을 구분해 표시한다', async () => {
    server.use(
      http.get(PENDING_APPROVALS_PATH, async () => {
        await delay(50)
        return pendingPage([])
      }),
    )
    renderPage()

    expect(
      await screen.findByText('승인 대기 제재를 불러오는 중입니다.'),
    ).toBeInTheDocument()
    expect(
      await screen.findByText('승인 대기 제재가 없습니다.'),
    ).toBeInTheDocument()
  })

  it('목록 조회 오류에서 다시 시도할 수 있다', async () => {
    let attempts = 0
    server.use(
      http.get(PENDING_APPROVALS_PATH, () => {
        attempts += 1
        return attempts === 1
          ? errorResponse(503, 'COMMON_012', '일시적으로 조회할 수 없습니다.')
          : pendingPage([])
      }),
    )
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '상태 다시 확인' }))
    expect(
      await screen.findByText('승인 대기 제재가 없습니다.'),
    ).toBeInTheDocument()
    expect(attempts).toBe(2)
  })

  it('목록 재조회가 403이면 stale 목록과 진행 중인 승인 상태를 폐기한다', async () => {
    let pendingRequests = 0
    let queryClient: QueryClient | undefined
    server.use(
      http.get(PENDING_APPROVALS_PATH, () => {
        pendingRequests += 1
        return pendingRequests === 2
          ? errorResponse(403, 'AUTH_011', '권한이 회수됐습니다.')
          : pendingPage()
      }),
    )
    renderPage((client) => {
      queryClient = client
    })
    await selectAndSubmit()
    expect(
      await screen.findByRole('dialog', { name: '재인증이 필요합니다' }),
    ).toBeInTheDocument()

    await act(async () => {
      await queryClient!.refetchQueries({ type: 'active' })
    })

    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText('선택한 제재')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '영구 정지 승인' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('dialog', { name: '재인증이 필요합니다' }),
    ).not.toBeInTheDocument()

    await act(async () => {
      await queryClient!.refetchQueries({ type: 'active' })
    })

    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.queryByText('선택한 제재')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('dialog', { name: '재인증이 필요합니다' }),
    ).not.toBeInTheDocument()
  })

  it('권한 회수 후 늦게 완료된 재인증으로 승인 명령을 보내지 않는다', async () => {
    let pendingRequests = 0
    let approvalRequests = 0
    let releaseReauthentication: (() => void) | undefined
    const reauthenticationPending = new Promise<void>((resolve) => {
      releaseReauthentication = resolve
    })
    let queryClient: QueryClient | undefined
    server.use(
      http.get(PENDING_APPROVALS_PATH, () => {
        pendingRequests += 1
        return pendingRequests === 1
          ? pendingPage()
          : errorResponse(403, 'AUTH_011', '권한이 회수됐습니다.')
      }),
      http.post(REAUTH_PATH, async () => {
        await reauthenticationPending
        return successResponse({
          approval: 'late-approval',
          expiresAt: '2026-08-17T10:05:00Z',
        })
      }),
      http.post(APPROVAL_PATH, () => {
        approvalRequests += 1
        return approvedSanction('APPLIED')
      }),
    )
    renderPage((client) => {
      queryClient = client
    })
    await selectAndSubmit()
    fireEvent.change(await screen.findByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '확인' })).toBeDisabled(),
    )

    await act(async () => {
      await queryClient!.refetchQueries({ type: 'active' })
    })
    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()

    releaseReauthentication!()
    await act(async () => {
      await delay(100)
    })

    expect(approvalRequests).toBe(0)
  })

  it('승인 권한이 없으면 목록 query와 폼을 열지 않는다', async () => {
    let pendingRequests = 0
    server.use(
      currentPlatformOperator({ permissions: ['MEMBER_READ_MINIMAL'] }),
      http.get(PENDING_APPROVALS_PATH, () => {
        pendingRequests += 1
        return pendingPage()
      }),
    )
    renderPage()

    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(pendingRequests).toBe(0)
    expect(
      screen.queryByRole('button', { name: '영구 정지 승인' }),
    ).not.toBeInTheDocument()
  })

  it('승인 권한이 있어도 슈퍼관리자 역할이 없으면 목록 query를 보내지 않는다', async () => {
    let pendingRequests = 0
    server.use(
      currentPlatformOperator({
        permissions: ['ACCOUNT_PERMANENT_SANCTION_APPROVE'],
        roles: ['MEMBER_SUPPORT_OPERATOR'],
      }),
      http.get(PENDING_APPROVALS_PATH, () => {
        pendingRequests += 1
        return pendingPage()
      }),
    )
    renderPage()

    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(pendingRequests).toBe(0)
  })
})
