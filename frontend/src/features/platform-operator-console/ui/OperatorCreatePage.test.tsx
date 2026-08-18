import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import { OperatorCreatePage } from './OperatorCreatePage'

function renderCreate() {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <MemoryRouter>
          <OperatorCreatePage />
        </MemoryRouter>
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
}

describe('운영자 계정 생성 권한', () => {
  it('생성 권한이 없으면 등록 폼을 보여 주지 않는다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({
        permissions: ['OPERATOR_AUTHORITY_MANAGE'],
      }),
    )
    renderCreate()

    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('form', { name: '운영자 등록' }),
    ).not.toBeInTheDocument()
  })

  it('생성 권한이 있으면 등록 폼을 보여 준다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['OPERATOR_CREATE'] }),
    )
    renderCreate()

    expect(
      await screen.findByRole('form', { name: '운영자 등록' }),
    ).toBeInTheDocument()
  })
})
