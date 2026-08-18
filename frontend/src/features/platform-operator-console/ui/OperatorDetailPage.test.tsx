import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
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
const AUTHORITY_PATH = `${DETAIL_PATH}/authority`
const REAUTH_PATH = '/api/v1/platform-operators/reauthentication-approvals'

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

  it('권한 교체 충돌 후 최신 authority version의 선택값으로 폼을 다시 초기화한다', async () => {
    let conflicted = false
    server.use(
      currentPlatformOperator({
        permissions: ['OPERATOR_AUTHORITY_MANAGE'],
      }),
      http.get(DETAIL_PATH, () =>
        successResponse(
          conflicted
            ? operatorAccountDetail({
                authorityVersion: 4,
                roles: ['ENFORCEMENT_OPERATOR'],
                directPermissions: ['MEMBER_RECOVERY'],
              })
            : operatorAccountDetail(),
        ),
      ),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-18T15:05:00Z',
        }),
      ),
      http.put(AUTHORITY_PATH, () => {
        conflicted = true
        return errorResponse(
          409,
          'COMMON_006',
          '다른 운영자가 먼저 권한을 변경했습니다.',
        )
      }),
    )
    renderDetail()

    const form = await screen.findByRole('form', {
      name: '역할·직접 권한 변경',
    })
    const roleGroup = within(form).getByRole('group', { name: '역할' })
    const permissionGroup = within(form).getByRole('group', {
      name: '직접 권한',
    })

    expect(within(roleGroup).getByLabelText('회원지원')).toBeChecked()
    expect(within(permissionGroup).getByLabelText('감사 조회')).toBeChecked()

    fireEvent.click(within(roleGroup).getByLabelText('회원지원'))
    fireEvent.click(within(roleGroup).getByLabelText('입점 심사'))
    fireEvent.change(within(form).getByLabelText('사건 ID'), {
      target: { value: 'case-200' },
    })
    fireEvent.change(within(form).getByLabelText('사건 version'), {
      target: { value: '3' },
    })
    fireEvent.click(within(form).getByRole('button', { name: '권한 교체' }))

    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => {
      expect(screen.getByText('4')).toBeInTheDocument()
      const refreshedForm = screen.getByRole('form', {
        name: '역할·직접 권한 변경',
      })
      const refreshedRoles = within(refreshedForm).getByRole('group', {
        name: '역할',
      })
      const refreshedPermissions = within(refreshedForm).getByRole('group', {
        name: '직접 권한',
      })
      expect(
        within(refreshedRoles).getByLabelText('회원지원'),
      ).not.toBeChecked()
      expect(
        within(refreshedRoles).getByLabelText('입점 심사'),
      ).not.toBeChecked()
      expect(within(refreshedRoles).getByLabelText('제재 집행')).toBeChecked()
      expect(
        within(refreshedPermissions).getByLabelText('감사 조회'),
      ).not.toBeChecked()
      expect(
        within(refreshedPermissions).getByLabelText('회원 복구'),
      ).toBeChecked()
    })
  })
})
