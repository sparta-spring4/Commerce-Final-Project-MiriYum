import { render, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, test } from 'vitest'
import { unauthenticatedConsumer } from '../features/auth/test/handlers'
import { storePage } from '../features/store-search/test/fixtures'
import { catalogHandlers } from '../features/store-search/test/handlers'
import { successResponse } from '../test/msw/envelope'
import { server } from '../test/msw/server'
import App from './App'

function renderAt(path: string) {
  window.history.pushState({}, '', path)
  render(<App />)
}

beforeEach(() => {
  // 홈은 catalog와 매장 미리보기를 부르고, 앱 셸은 항상 세션 복구를 시도한다.
  // 등록하지 않으면 MSW가 처리하지 않은 요청으로 잡는다.
  server.use(
    ...catalogHandlers,
    unauthenticatedConsumer,
    http.get('/api/v1/stores', () => successResponse(storePage([]))),
  )
})

describe('앱 셸', () => {
  test('홈이 매장 검색 진입점 역할을 한다', () => {
    renderAt('/')

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('미리냠')
    expect(screen.getByRole('form', { name: '매장 검색 조건' })).toBeInTheDocument()
  })

  test('홈에 큐레이션·추천 목록을 만들지 않는다', () => {
    // 1차 MVP 계약에 큐레이션 기준이 없다. 이력 추천은 2차 MVP다.
    renderAt('/')

    for (const label of ['오늘의 추천', '추천 맛집', '인기 매장']) {
      expect(screen.queryByText(label)).not.toBeInTheDocument()
    }
  })

  test('공개 shell의 네비게이션에 뒤 단계 기능 항목이 없다', () => {
    renderAt('/')

    const nav = screen.getByRole('navigation', { name: '주 메뉴' })

    for (const label of ['웨이팅', '결제', '리뷰', '채팅', '알림']) {
      expect(nav).not.toHaveTextContent(label)
    }
  })

  test('비로그인 사용자에게 로그인·회원가입 진입점을 보여 준다', async () => {
    renderAt('/')

    // 푸터에도 같은 이름의 링크가 있으므로 헤더로 범위를 좁힌다.
    const header = within(screen.getByRole('banner'))

    await waitFor(() =>
      expect(header.getByRole('link', { name: '로그인' })).toBeInTheDocument(),
    )
    expect(header.getByRole('link', { name: '회원가입' })).toBeInTheDocument()
  })

  test('공개 화면은 비로그인 상태에서도 로그인으로 튕기지 않는다', async () => {
    renderAt('/')

    await waitFor(() =>
      expect(
        within(screen.getByRole('banner')).getByRole('link', { name: '로그인' }),
      ).toBeInTheDocument(),
    )
    expect(
      screen.getByRole('form', { name: '매장 검색 조건' }),
    ).toBeInTheDocument()
  })

  test('없는 경로는 404 화면을 보여 준다', () => {
    renderAt('/이런-경로는-없다')

    expect(
      screen.getByRole('heading', { level: 1, name: '페이지를 찾을 수 없습니다' }),
    ).toBeInTheDocument()
  })

  test('권한 없음은 404와 다른 화면이다', () => {
    renderAt('/forbidden')

    expect(
      screen.getByRole('heading', { level: 1, name: '접근할 수 없습니다' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('페이지를 찾을 수 없습니다')).not.toBeInTheDocument()
  })
})
