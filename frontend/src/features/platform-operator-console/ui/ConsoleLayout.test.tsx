import { render, screen } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { errorResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  PO_ME_PATH,
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import { ConsoleLayout } from './ConsoleLayout'

function renderLayout() {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <MemoryRouter initialEntries={['/admin']}>
          <Routes>
            <Route element={<ConsoleLayout />}>
              <Route path="/admin" element={<p>업무 화면</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
}

describe('운영 콘솔 권한 경계', () => {
  it('현재 운영자 조회가 실패하면 업무 화면을 열지 않는다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      http.get(PO_ME_PATH, () =>
        errorResponse(503, 'COMMON_012', '서비스를 사용할 수 없습니다.'),
      ),
    )
    renderLayout()

    expect(
      await screen.findByText('현재 운영자 권한을 불러오지 못했습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('업무 화면')).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '상태 다시 확인' }),
    ).toBeInTheDocument()
  })

  it('서버가 준 권한에 해당하는 메뉴와 업무 화면만 연다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['MEMBER_READ_MINIMAL'] }),
    )
    renderLayout()

    expect(await screen.findByText('업무 화면')).toBeInTheDocument()
    expect(screen.queryByText('김운영')).not.toBeInTheDocument()
    expect(screen.getByText('회원지원')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '회원 관리' })).toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: '운영자 관리' }),
    ).not.toBeInTheDocument()
  })
})
