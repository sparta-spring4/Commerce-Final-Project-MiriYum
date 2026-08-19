import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
  unauthenticatedPlatformOperator,
} from '../domains/account/platform-operator/auth/test/handlers'
import { successResponse } from '../test/msw/envelope'
import { server } from '../test/msw/server'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.resetModules()
})

describe('플랫폼 운영자 route context', () => {
  test('기능 플래그가 켜지면 /admin/login이 콘솔 로그인 화면에 매칭된다', async () => {
    vi.stubEnv('VITE_PLATFORM_OPERATOR_ENABLED', 'true')
    server.use(unauthenticatedPlatformOperator)
    window.history.pushState({}, '', '/admin/login')

    const { default: App } = await import('./App')
    render(<App />)

    expect(
      await screen.findByRole(
        'heading',
        { name: '운영자 로그인' },
        { timeout: 10_000 },
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText('페이지를 찾을 수 없습니다')).not.toBeInTheDocument()
  }, 15_000)

  test('관리자 상세 경로에서도 소비자 세션 복구를 호출하지 않는다', async () => {
    vi.stubEnv('VITE_PLATFORM_OPERATOR_ENABLED', 'true')
    let consumerRefreshCalls = 0
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['MEMBER_READ_MINIMAL'] }),
      http.post('/api/v1/consumers/auth/token-refreshes', () => {
        consumerRefreshCalls += 1
        return successResponse(null)
      }),
      http.get('/api/v1/platform-operators/members', () =>
        successResponse({
          content: [],
          number: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          hasNext: false,
        }),
      ),
    )
    window.history.pushState({}, '', '/admin/members')

    const { default: App } = await import('./App')
    render(<App />)

    await waitFor(
      () =>
        expect(
          screen.getByRole('heading', { name: '회원 관리' }),
        ).toBeInTheDocument(),
      { timeout: 10_000 },
    )
    expect(consumerRefreshCalls).toBe(0)
  }, 15_000)

  test.each([
    ['/admin/members', '/api/v1/platform-operators/members'],
    [
      '/admin/members/CONSUMER/member-1001',
      '/api/v1/platform-operators/members/CONSUMER/member-1001',
    ],
    [
      '/admin/member-support-cases',
      '/api/v1/platform-operators/member-support-cases',
    ],
    [
      '/admin/member-support-cases/case-1001',
      '/api/v1/platform-operators/member-support-cases/case-1001',
    ],
    ['/admin/audit', '/api/v1/platform-operators/audit-events'],
    [
      '/admin/audit/event-1001',
      '/api/v1/platform-operators/audit-events/event-1001',
    ],
  ])(
    '조회 권한 없이 %s에 직접 접근하면 업무 API를 호출하지 않는다',
    async (pagePath, apiPath) => {
      vi.stubEnv('VITE_PLATFORM_OPERATOR_ENABLED', 'true')
      let requests = 0
      server.use(
        authenticatedPlatformOperator(),
        currentPlatformOperator({ permissions: [] }),
        http.get(apiPath, () => {
          requests += 1
          return successResponse(null)
        }),
      )
      window.history.pushState({}, '', pagePath)

      const { default: App } = await import('./App')
      render(<App />)

      expect(
        await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
      ).toBeInTheDocument()
      expect(requests).toBe(0)
    },
  )

  test('회원 조회 권한만 있으면 상세는 보되 제재 명령 UI는 노출하지 않는다', async () => {
    vi.stubEnv('VITE_PLATFORM_OPERATOR_ENABLED', 'true')
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['MEMBER_READ_MINIMAL'] }),
      http.get(
        '/api/v1/platform-operators/members/CONSUMER/member-1001',
        () =>
          successResponse({
            accountType: 'CONSUMER',
            accountId: 'member-1001',
            status: 'ACTIVE',
            joinedAt: '2026-08-18T10:00:00+09:00',
            supportVersion: 3,
            activeSanctions: [],
          }),
      ),
    )
    window.history.pushState(
      {},
      '',
      '/admin/members/CONSUMER/member-1001',
    )

    const { default: App } = await import('./App')
    render(<App />)

    expect(
      await screen.findByRole('heading', { name: '회원 상세' }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('form', { name: '제재 적용' }),
    ).not.toBeInTheDocument()
  })

  test('기본 제재 운영자 권한이면 회원 상세에 진입해 제재 명령을 사용할 수 있다', async () => {
    vi.stubEnv('VITE_PLATFORM_OPERATOR_ENABLED', 'true')
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({
        roles: ['ENFORCEMENT_OPERATOR'],
        permissions: [
          'MEMBER_READ_MINIMAL',
          'ACCOUNT_SANCTION',
          'STORE_READ_MINIMAL',
          'STORE_SANCTION',
        ],
      }),
      http.get(
        '/api/v1/platform-operators/members/CONSUMER/member-1001',
        () =>
          successResponse({
            accountType: 'CONSUMER',
            accountId: 'member-1001',
            status: 'ACTIVE',
            joinedAt: '2026-08-18T10:00:00+09:00',
            supportVersion: 3,
            activeSanctions: [],
          }),
      ),
    )
    window.history.pushState(
      {},
      '',
      '/admin/members/CONSUMER/member-1001',
    )

    const { default: App } = await import('./App')
    render(<App />)

    expect(
      await screen.findByRole('heading', { name: '회원 상세' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: '회원 관리' }),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('form', { name: '제재 적용' }),
    ).toBeInTheDocument()
  })

  test('감사 조회 권한만 있으면 상세는 보되 보정 명령 UI는 노출하지 않는다', async () => {
    vi.stubEnv('VITE_PLATFORM_OPERATOR_ENABLED', 'true')
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['AUDIT_READ'] }),
      http.get(
        '/api/v1/platform-operators/audit-events/event-1001',
        () =>
          successResponse({
            original: {
              eventKey: 'event-1001',
              source: 'PLATFORM_OPERATOR',
              action: 'OPERATOR_AUTHORITY_CHANGED',
              outcome: 'SUCCESS',
              actorOperatorId: 'operator-1001',
              authorityVersion: 3,
              roles: [],
              permissions: ['AUDIT_READ'],
              beforeRoles: [],
              afterRoles: [],
              beforePermissions: [],
              afterPermissions: [],
              targetType: 'PLATFORM_OPERATOR',
              targetId: 'operator-1002',
              reason: '정기 감사',
              occurredAt: '2026-08-18T10:00:00+09:00',
            },
            corrections: [],
          }),
      ),
    )
    window.history.pushState({}, '', '/admin/audit/event-1001')

    const { default: App } = await import('./App')
    render(<App />)

    fireEvent.change(await screen.findByLabelText('감사 사건 ID'), {
      target: { value: 'audit-case-1001' },
    })
    fireEvent.change(screen.getByLabelText('사건 version'), {
      target: { value: '4' },
    })
    fireEvent.click(screen.getByRole('button', { name: '조회 시작' }))

    expect(await screen.findByText('정기 감사')).toBeInTheDocument()
    expect(
      screen.queryByRole('form', { name: '보정 사건 추가' }),
    ).not.toBeInTheDocument()
  })
})
