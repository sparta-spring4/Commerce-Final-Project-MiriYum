import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { AuthErrorCode } from '../../auth/model/authErrors'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import { SanctionForm } from './SanctionForm'

const SANCTION_PATH =
  '/api/v1/platform-operators/members/CONSUMER/member-1/sanctions'
const REAUTH_PATH = '/api/v1/platform-operators/reauthentication-approvals'

function renderForm(onApplied = vi.fn()) {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <SanctionForm
          accountType="CONSUMER"
          accountId="member-1"
          supportVersion={3}
          onApplied={onApplied}
        />
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
  return onApplied
}

/** 유효한 입력을 채우고 제출한다. 제출은 재인증 다이얼로그를 열 뿐이다. */
function fillAndSubmit() {
  fireEvent.change(screen.getByLabelText('사유 코드'), {
    target: { value: 'ABUSE_REPORT' },
  })
  fireEvent.change(screen.getByLabelText('정책 version'), {
    target: { value: 'SANCTION_POLICY_V1' },
  })
  fireEvent.click(screen.getByRole('button', { name: '제재 적용' }))
}

describe('회원 제재 적용', () => {
  beforeEach(() => {
    server.use(currentPlatformOperator())
  })

  it('재인증 전에는 제재를 보내지 않는다', async () => {
    const sanctionRequests: Request[] = []
    server.use(
      authenticatedPlatformOperator(),
      http.post(SANCTION_PATH, ({ request }) => {
        sanctionRequests.push(request)
        return successResponse({})
      }),
    )
    renderForm()

    fillAndSubmit()

    // 다이얼로그가 열렸을 뿐 명령은 아직 나가지 않았다.
    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    expect(sanctionRequests).toHaveLength(0)
  })

  it('재인증 승인을 받은 뒤에야 계약이 요구하는 헤더를 모두 보낸다', async () => {
    let sanctionHeaders: Headers | null = null
    server.use(
      authenticatedPlatformOperator(),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-17T10:05:00Z',
        }),
      ),
      http.post(SANCTION_PATH, ({ request }) => {
        sanctionHeaders = request.headers
        return successResponse({
          sanctionId: 'sanction-1',
          accountType: 'CONSUMER',
          accountId: 'member-1',
          level: 'WARNING',
          status: 'APPLIED',
          policyVersion: 'SANCTION_POLICY_V1',
          version: 1,
          restrictedFeatures: [],
          proposedAt: '2026-08-17T09:00:00Z',
        })
      }),
    )
    const onApplied = renderForm()

    fillAndSubmit()
    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })

    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect(sanctionHeaders).not.toBeNull())
    const headers = sanctionHeaders!
    expect(headers.get('X-Admin-Reauthentication')).toBe('approval-1')
    // 대상 version을 그대로 보낸다. 빠지면 마지막 쓰기가 이긴다.
    expect(headers.get('If-Match')).toBe('3')
    expect(headers.get('Idempotency-Key')).not.toBeNull()

    // 성공을 화면이 확정하지 않고 부모가 서버에서 다시 읽는다.
    await waitFor(() => expect(onApplied).toHaveBeenCalled())
  })

  /**
   * 영구 정지는 제안일 뿐 적용이 아니다. 화면이 "정지 완료"로 단정하면
   * 실제로는 다른 슈퍼관리자의 승인을 기다리는 제재를 처리됐다고 오인한다.
   */
  it('영구 정지 제안 결과를 적용 완료로 표시하지 않는다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-17T10:05:00Z',
        }),
      ),
      http.post(SANCTION_PATH, () =>
        successResponse({
          sanctionId: 'sanction-9',
          accountType: 'CONSUMER',
          accountId: 'member-1',
          level: 'PERMANENT_SUSPENSION',
          status: 'PENDING_ADDITIONAL_APPROVAL',
          policyVersion: 'SANCTION_POLICY_V1',
          version: 1,
          restrictedFeatures: [],
          proposedAt: '2026-08-17T09:00:00Z',
        }),
      ),
    )
    renderForm()

    fireEvent.change(screen.getByLabelText('제재 수준'), {
      target: { value: 'PERMANENT_SUSPENSION' },
    })
    fireEvent.change(screen.getByLabelText('사유 코드'), {
      target: { value: 'ABUSE_REPORT' },
    })
    fireEvent.change(screen.getByLabelText('정책 version'), {
      target: { value: 'SANCTION_POLICY_V1' },
    })
    fireEvent.click(screen.getByRole('button', { name: '영구 정지 제안' }))

    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    const notice = await screen.findByText(/추가 승인 대기로 제안했습니다/)
    expect(notice).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '전달용 값 복사' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByText(/승인자에게 아래 값을 전달해 주세요/),
    ).not.toBeInTheDocument()
  })

  /**
   * 승인 실패 뒤 다시 시도할 때 멱등 키가 바뀌면 서버가 같은 제재를
   * 두 건으로 본다.
   */
  it('실패 후 재시도에서 같은 멱등 키를 유지한다', async () => {
    const keys: string[] = []
    let attempt = 0
    server.use(
      authenticatedPlatformOperator(),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: `approval-${(attempt += 1)}`,
          expiresAt: '2026-08-17T10:05:00Z',
        }),
      ),
      http.post(SANCTION_PATH, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        return errorResponse(
          503,
          'COMMON_012',
          '일시적으로 처리할 수 없습니다.',
        )
      }),
    )
    renderForm()

    for (let round = 0; round < 2; round += 1) {
      fillAndSubmit()
      await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
      fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
        target: { value: 'Miriyum1!' },
      })
      fireEvent.click(screen.getByRole('button', { name: '확인' }))
      await waitFor(() => expect(keys).toHaveLength(round + 1))
    }

    expect(keys[0]).toBe(keys[1])
  })

  it('실패 후 제재 입력을 바꾸면 새 멱등 키를 사용한다', async () => {
    const keys: string[] = []
    let approvalSequence = 0
    server.use(
      authenticatedPlatformOperator(),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: `approval-${(approvalSequence += 1)}`,
          expiresAt: '2026-08-17T10:05:00Z',
        }),
      ),
      http.post(SANCTION_PATH, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        return errorResponse(
          503,
          'COMMON_012',
          '일시적으로 처리할 수 없습니다.',
        )
      }),
    )
    renderForm()

    fillAndSubmit()
    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))
    await waitFor(() => expect(keys).toHaveLength(1))

    fireEvent.change(screen.getByLabelText('사유 코드'), {
      target: { value: 'FRAUD_REPORT' },
    })
    fireEvent.click(screen.getByRole('button', { name: '제재 적용' }))
    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))
    await waitFor(() => expect(keys).toHaveLength(2))

    expect(keys[1]).not.toBe(keys[0])
  })

  it('version 충돌이면 최신 상태를 다시 읽게 한다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-17T10:05:00Z',
        }),
      ),
      http.post(SANCTION_PATH, () =>
        errorResponse(
          409,
          AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT,
          '계정 또는 사건 상태가 변경되었습니다.',
        ),
      ),
    )
    const onApplied = renderForm()

    fillAndSubmit()
    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await screen.findByText(/대상 상태가 변경됐습니다/)
    await waitFor(() => expect(onApplied).toHaveBeenCalled())
  })

  it('사유 코드 형식이 계약과 다르면 보내기 전에 막는다', async () => {
    const sanctionRequests: Request[] = []
    server.use(
      authenticatedPlatformOperator(),
      http.post(SANCTION_PATH, ({ request }) => {
        sanctionRequests.push(request)
        return successResponse({})
      }),
    )
    renderForm()

    fireEvent.change(screen.getByLabelText('사유 코드'), {
      target: { value: 'abuse report' },
    })
    fireEvent.change(screen.getByLabelText('정책 version'), {
      target: { value: 'SANCTION_POLICY_V1' },
    })
    fireEvent.click(screen.getByRole('button', { name: '제재 적용' }))

    await screen.findByText('대문자·숫자·밑줄만 사용할 수 있습니다.')
    expect(
      screen.queryByRole('dialog', { name: '재인증이 필요합니다' }),
    ).not.toBeInTheDocument()
    expect(sanctionRequests).toHaveLength(0)
  })
})
