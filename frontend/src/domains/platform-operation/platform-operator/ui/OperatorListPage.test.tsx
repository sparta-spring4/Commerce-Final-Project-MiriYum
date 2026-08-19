import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { MemoryRouter } from 'react-router'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { TestQueryProvider } from '../../../../test/TestQueryProvider'
import { PlatformOperatorAuthProvider } from '../../../account/platform-operator/auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../../account/platform-operator/auth/test/handlers'
import { OperatorListPage } from './OperatorListPage'
import { operatorAccountPage } from '../test/operatorFixtures'

const ACCOUNTS_PATH = '/api/v1/platform-operators/accounts'

function renderList() {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <MemoryRouter initialEntries={['/admin/operators']}>
          <OperatorListPage />
        </MemoryRouter>
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
}

describe('운영자 계정 목록', () => {
  beforeEach(() => {
    server.use(
      currentPlatformOperator({
        permissions: ['OPERATOR_AUTHORITY_MANAGE', 'OPERATOR_CREATE'],
      }),
    )
  })

  it('서버가 준 마스킹 이메일을 그대로 표시한다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      http.get(ACCOUNTS_PATH, () => successResponse(operatorAccountPage())),
    )
    renderList()

    // 마스킹된 값이 그대로 보인다. 화면이 복원하거나 가공하지 않는다.
    expect(await screen.findByText('op***@miriyum.hq')).toBeInTheDocument()
    expect(screen.getByText('김운영')).toBeInTheDocument()
  })

  it('생성 권한이 없으면 운영자 등록 진입점을 보여 주지 않는다', async () => {
    server.use(
      currentPlatformOperator({
        permissions: ['OPERATOR_AUTHORITY_MANAGE'],
      }),
      authenticatedPlatformOperator(),
      http.get(ACCOUNTS_PATH, () => successResponse(operatorAccountPage())),
    )
    renderList()

    await screen.findByText('김운영')
    expect(
      screen.queryByRole('link', { name: '운영자 등록' }),
    ).not.toBeInTheDocument()
  })

  /**
   * 권한 없음은 재시도해도 결과가 같다. "다시 시도"를 주면 거부 요청만
   * 반복되고 그 거부가 감사 원장에 쌓인다.
   */
  it('403은 일반 오류와 구분해 권한 안내를 보여 준다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      http.get(ACCOUNTS_PATH, () =>
        errorResponse(403, 'ADMIN_001', '권한이 없습니다.'),
      ),
    )
    renderList()

    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '다시 시도' }),
    ).not.toBeInTheDocument()
  })

  it('결과가 없으면 조건을 바꾸도록 안내한다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      http.get(ACCOUNTS_PATH, () =>
        successResponse(operatorAccountPage({ content: [] })),
      ),
    )
    renderList()

    expect(
      await screen.findByText('조건에 맞는 운영자가 없습니다.'),
    ).toBeInTheDocument()
  })

  /**
   * 검색은 입력할 때마다 나가지 않는다. 운영자 조회는 권한이 필요한 요청이라
   * 타이핑 중 매 글자마다 보내면 불필요한 조회가 쌓인다.
   */
  it('검색어는 제출했을 때만 query로 전송된다', async () => {
    const sentQueries: (string | null)[] = []
    server.use(
      authenticatedPlatformOperator(),
      http.get(ACCOUNTS_PATH, ({ request }) => {
        sentQueries.push(new URL(request.url).searchParams.get('query'))
        return successResponse(operatorAccountPage())
      }),
    )
    renderList()

    await screen.findByText('김운영')
    const before = sentQueries.length

    fireEvent.change(screen.getByLabelText('검색'), {
      target: { value: '김운영' },
    })
    // 입력만으로는 새 요청이 나가지 않는다.
    expect(sentQueries).toHaveLength(before)

    fireEvent.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => expect(sentQueries.length).toBeGreaterThan(before))
    expect(sentQueries[sentQueries.length - 1]).toBe('김운영')
  })

  it('허용된 정렬 값만 서버로 보낸다', async () => {
    const sentSorts: (string | null)[] = []
    server.use(
      authenticatedPlatformOperator(),
      http.get(ACCOUNTS_PATH, ({ request }) => {
        sentSorts.push(new URL(request.url).searchParams.get('sort'))
        return successResponse(operatorAccountPage())
      }),
    )
    renderList()

    await screen.findByText('김운영')

    fireEvent.change(screen.getByLabelText('정렬'), {
      target: { value: 'lastLoginAt,desc' },
    })

    await waitFor(() =>
      expect(sentSorts).toContain('lastLoginAt,desc'),
    )
  })
})
