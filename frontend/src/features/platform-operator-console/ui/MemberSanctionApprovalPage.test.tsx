import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { AuthErrorCode } from '../../auth/model/authErrors'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import { MemberSanctionApprovalPage } from './MemberSanctionApprovalPage'

const REAUTH_PATH = '/api/v1/platform-operators/reauthentication-approvals'
const APPROVAL_PATH =
  '/api/v1/platform-operators/member-sanctions/sanction-1/additional-approvals'

function renderPage() {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <MemberSanctionApprovalPage />
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
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

/** 유효한 입력을 채우고 제출한다. 제출은 재인증 다이얼로그를 열 뿐이다. */
async function fillAndSubmit() {
  fireEvent.change(await screen.findByLabelText('대상 계정 유형'), {
    target: { value: 'CONSUMER' },
  })
  fireEvent.change(screen.getByLabelText('대상 계정 ID'), {
    target: { value: 'member-1' },
  })
  fireEvent.change(screen.getByLabelText('제재 ID'), {
    target: { value: 'sanction-1' },
  })
  fireEvent.change(screen.getByLabelText('제재 version'), {
    target: { value: '4' },
  })
  fireEvent.change(screen.getByLabelText('승인 사유 코드'), {
    target: { value: 'POLICY_CONFIRMED' },
  })
  fireEvent.click(screen.getByRole('button', { name: '영구 정지 승인' }))
}

describe('영구 정지 추가 승인', () => {
  beforeEach(() => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({
        permissions: ['ACCOUNT_PERMANENT_SANCTION_APPROVE'],
        roles: ['SUPER_ADMIN'],
      }),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-17T10:05:00Z',
        }),
      ),
    )
  })

  /**
   * 승인 대기 제재를 조회하는 계약이 없다(#425). 목록을 흉내 내면 화면이
   * 만들어 낸 데이터를 실제 대기 건으로 오인하게 된다.
   */
  it('승인 대기 목록을 만들지 않고 그 사실을 알린다', async () => {
    renderPage()

    expect(
      await screen.findByText('승인 대기 목록은 제공되지 않습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('재인증 승인 뒤에 If-Match와 재인증 헤더를 함께 보낸다', async () => {
    let headers: Headers | null = null
    server.use(
      http.post(APPROVAL_PATH, ({ request }) => {
        headers = request.headers
        return approvedSanction('APPLIED')
      }),
    )
    renderPage()
    await fillAndSubmit()

    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect(headers).not.toBeNull())
    const sent = headers!
    expect(sent.get('X-Admin-Reauthentication')).toBe('approval-1')
    expect(sent.get('If-Match')).toBe('4')
    expect(sent.get('Idempotency-Key')).not.toBeNull()

    // 서버가 준 status를 그대로 전한다.
    expect(
      await screen.findByText('제재 sanction-1가 APPLIED 상태가 됐습니다.'),
    ).toBeInTheDocument()
  })

  it('재인증을 제재 대상 회원 유형과 계정 ID에 결속한다', async () => {
    let reauthenticationBody: unknown = null
    server.use(
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

    fireEvent.change(await screen.findByLabelText('대상 계정 유형'), {
      target: { value: 'STORE_OPERATOR' },
    })
    fireEvent.change(screen.getByLabelText('대상 계정 ID'), {
      target: { value: '9001' },
    })
    fireEvent.change(screen.getByLabelText('제재 ID'), {
      target: { value: 'sanction-1' },
    })
    fireEvent.change(screen.getByLabelText('제재 version'), {
      target: { value: '4' },
    })
    fireEvent.change(screen.getByLabelText('승인 사유 코드'), {
      target: { value: 'POLICY_CONFIRMED' },
    })
    fireEvent.click(screen.getByRole('button', { name: '영구 정지 승인' }))

    fireEvent.change(await screen.findByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect(reauthenticationBody).not.toBeNull())
    expect(reauthenticationBody).toMatchObject({
      purpose: 'PERMANENT_ACCOUNT_SANCTION_APPROVAL',
      targetType: 'STORE_OPERATOR_ACCOUNT',
      targetId: '9001',
    })
  })

  it('입력이 비어 있으면 재인증도 열지 않는다', async () => {
    let requested = 0
    server.use(
      http.post(APPROVAL_PATH, () => {
        requested += 1
        return approvedSanction('APPLIED')
      }),
    )
    renderPage()

    fireEvent.click(
      await screen.findByRole('button', { name: '영구 정지 승인' }),
    )

    expect(
      await screen.findByText('제재 ID를 입력해 주세요.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('dialog', { name: '재인증이 필요합니다' }),
    ).not.toBeInTheDocument()
    expect(requested).toBe(0)
  })

  /** 제안자 본인이면 서버가 403으로 막는다. 화면은 그 이유를 그대로 전한다. */
  it('제안자 본인 승인 거부를 사용자 언어로 전한다', async () => {
    server.use(
      http.post(APPROVAL_PATH, () =>
        errorResponse(403, 'AUTH_014', '본인이 제안한 제재입니다.'),
      ),
    )
    renderPage()
    await fillAndSubmit()

    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    expect(
      await screen.findByText(
        '승인 권한이 없거나 제안자 본인이라 승인할 수 없습니다.',
      ),
    ).toBeInTheDocument()
  })

  it('이미 종결된 제안이면 최신 상태 확인을 안내한다', async () => {
    server.use(
      http.post(APPROVAL_PATH, () =>
        errorResponse(
          409,
          AuthErrorCode.PERMANENT_SANCTION_APPROVAL_CONFLICT,
          '이미 처리된 제재입니다.',
        ),
      ),
    )
    renderPage()
    await fillAndSubmit()

    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    expect(
      await screen.findByText(
        '이미 승인됐거나 종결된 제안입니다. 최신 상태를 확인해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('승인 권한이 없으면 폼을 열지 않는다', async () => {
    server.use(
      currentPlatformOperator({ permissions: ['MEMBER_READ_MINIMAL'] }),
    )
    renderPage()

    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '영구 정지 승인' }),
    ).not.toBeInTheDocument()
  })

  it('승인 권한이 있어도 슈퍼관리자 역할이 없으면 폼을 열지 않는다', async () => {
    server.use(
      currentPlatformOperator({
        permissions: ['ACCOUNT_PERMANENT_SANCTION_APPROVE'],
        roles: ['MEMBER_SUPPORT_OPERATOR'],
      }),
    )
    renderPage()

    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '영구 정지 승인' }),
    ).not.toBeInTheDocument()
  })
})
