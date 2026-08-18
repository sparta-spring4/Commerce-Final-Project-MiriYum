import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import {
  storeSanction,
  storeSanctionImpactPreview,
} from '../test/storeFixtures'
import { StoreSanctionForm } from './StoreSanctionForm'

const CASE_BASE =
  '/api/v1/platform-operators/stores/4001/sanction-cases/case-7001'
const PREVIEW_PATH = `${CASE_BASE}/impact-previews`
const SANCTION_PATH = `${CASE_BASE}/sanctions`
const REAUTH_PATH = '/api/v1/platform-operators/reauthentication-approvals'

const CONTEXT = {
  caseId: 'case-7001',
  caseVersion: 3,
  reasonCode: 'STORE_ENFORCEMENT',
}

function renderForm(onApplied = vi.fn()) {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <StoreSanctionForm
          storeId={4001}
          context={CONTEXT}
          onApplied={onApplied}
        />
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
  return onApplied
}

function fillReason() {
  fireEvent.change(screen.getByLabelText('제재 사유'), {
    target: { value: '반복 노쇼' },
  })
}

function selectType(value: string) {
  fireEvent.change(screen.getByLabelText('제재 유형'), { target: { value } })
}

describe('매장 제재 적용', () => {
  beforeEach(() => {
    server.use(authenticatedPlatformOperator(), currentPlatformOperator())
  })

  it('영향 미리보기 전에는 실행 버튼 자체가 없다', () => {
    renderForm()

    expect(
      screen.queryByRole('button', { name: '제재 적용' }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '영향 미리보기' }),
    ).toBeInTheDocument()
  })

  it('영향 건수를 보여 준 뒤에 실행할 수 있다', async () => {
    let sanctionBody: unknown = null
    server.use(
      http.post(PREVIEW_PATH, () =>
        successResponse(storeSanctionImpactPreview()),
      ),
      http.post(SANCTION_PATH, async ({ request }) => {
        sanctionBody = await request.json()
        return successResponse(storeSanction({ status: 'ACTIVE' }))
      }),
    )
    renderForm()

    fillReason()
    fireEvent.click(screen.getByRole('button', { name: '영향 미리보기' }))

    // 취소될 건수를 먼저 보여 준다.
    expect(await screen.findByText('12')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '제재 적용' }))
    await waitFor(() => expect(sanctionBody).not.toBeNull())

    // 미리보기 결과를 그대로 확인값으로 붙인다. 화면이 지어내지 않는다.
    expect(sanctionBody).toMatchObject({
      impactConfirmation: {
        previewId: 3001,
        previewDigest: 'digest-abc',
        caseVersion: 3,
        storeEnforcementVersion: 7,
      },
    })
  })

  /** 영구 퇴점은 서버가 재인증을 요구한다. 화면도 그 단계를 건너뛰지 않는다. */
  it('영구 퇴점은 재인증 없이 실행되지 않는다', async () => {
    let sanctionHeaders: Headers | null = null
    server.use(
      http.post(PREVIEW_PATH, () =>
        successResponse(storeSanctionImpactPreview()),
      ),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-17T10:05:00Z',
        }),
      ),
      http.post(SANCTION_PATH, ({ request }) => {
        sanctionHeaders = request.headers
        return successResponse(
          storeSanction({ type: 'PERMANENT_EXIT', status: 'PENDING_APPROVAL' }),
        )
      }),
    )
    renderForm()

    selectType('PERMANENT_EXIT')
    fillReason()
    // 고위험이라는 사실을 실행 전에 알린다.
    expect(
      screen.getByText(
        '선택한 제재는 고위험이라 제안자와 다른 슈퍼관리자의 추가 승인이 필요합니다.',
      ),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '영향 미리보기' }))
    fireEvent.click(await screen.findByRole('button', { name: '제재 제안' }))

    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    expect(sanctionHeaders).toBeNull()

    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect(sanctionHeaders).not.toBeNull())
    expect(sanctionHeaders!.get('X-Admin-Reauthentication')).toBe('approval-1')
    expect(sanctionHeaders!.get('Idempotency-Key')).not.toBeNull()

    // 제안은 적용이 아니다. 서버가 준 상태를 그대로 전한다.
    expect(
      await screen.findByText(
        '제재 5001를 제안했습니다. 다른 슈퍼관리자의 승인 후 적용됩니다.',
      ),
    ).toBeInTheDocument()
  })

  /** 기간 정지도 서버 정책상 고위험이며 다른 슈퍼관리자의 승인을 받는다. */
  it('기간 정지는 재인증 없이 실행되지 않는다', async () => {
    let sanctionRequested = false
    server.use(
      http.post(PREVIEW_PATH, () =>
        successResponse(storeSanctionImpactPreview()),
      ),
      http.post(SANCTION_PATH, () => {
        sanctionRequested = true
        return successResponse(
          storeSanction({
            type: 'TEMPORARY_SUSPENSION',
            status: 'PENDING_APPROVAL',
          }),
        )
      }),
    )
    renderForm()

    selectType('TEMPORARY_SUSPENSION')
    fillReason()
    fireEvent.click(screen.getByRole('button', { name: '영향 미리보기' }))
    fireEvent.click(await screen.findByRole('button', { name: '제재 제안' }))

    expect(
      await screen.findByRole('dialog', { name: '재인증이 필요합니다' }),
    ).toBeInTheDocument()
    expect(sanctionRequested).toBe(false)
  })

  /**
   * 409는 그 사이 매장·사건 상태가 바뀌었다는 뜻이다. 같은 미리보기로 다시
   * 보내면 이미 낡은 영향 확인값을 그대로 재사용하게 된다.
   */
  it('충돌이 나면 미리보기를 버리고 다시 만들게 한다', async () => {
    server.use(
      http.post(PREVIEW_PATH, () =>
        successResponse(storeSanctionImpactPreview()),
      ),
      http.post(SANCTION_PATH, () =>
        errorResponse(409, 'STORE_009', '사건 상태가 변경됐습니다.'),
      ),
    )
    renderForm()

    fillReason()
    fireEvent.click(screen.getByRole('button', { name: '영향 미리보기' }))
    fireEvent.click(await screen.findByRole('button', { name: '제재 적용' }))

    expect(
      await screen.findByText(
        '사건 또는 매장 상태가 변경됐습니다. 최신 상태를 다시 확인하고 영향 미리보기를 새로 만들어 주세요.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '제재 적용' }),
    ).not.toBeInTheDocument()
  })

  it('기능 제한인데 기능을 고르지 않으면 미리보기를 보내지 않는다', async () => {
    let requested = 0
    server.use(
      http.post(PREVIEW_PATH, () => {
        requested += 1
        return successResponse(storeSanctionImpactPreview())
      }),
    )
    renderForm()

    selectType('FEATURE_RESTRICTION')
    fillReason()
    fireEvent.click(screen.getByRole('button', { name: '영향 미리보기' }))

    expect(
      await screen.findByText('제한할 기능을 하나 이상 선택해 주세요.'),
    ).toBeInTheDocument()
    expect(requested).toBe(0)
  })
})
