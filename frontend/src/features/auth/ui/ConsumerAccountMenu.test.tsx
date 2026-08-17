import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { ConsumerAuthProvider } from '../ConsumerAuthProvider'
import {
  authenticatedConsumer,
  signOutHandlers,
  unauthenticatedConsumer,
} from '../test/handlers'
import { ConsumerAccountMenu } from './ConsumerAccountMenu'

function LocationProbe() {
  const { pathname } = useLocation()
  return <p data-testid="location">{pathname}</p>
}

function renderMenu(route: string = ROUTES.home) {
  return render(
    <TestQueryProvider>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <ConsumerAccountMenu />
          <Routes>
            <Route path={ROUTES.home} element={<LocationProbe />} />
            <Route path={ROUTES.myPage} element={<LocationProbe />} />
            <Route path={ROUTES.myReservations} element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </TestQueryProvider>,
  )
}

/** 세션 복구가 끝나 계정 버튼이 나타날 때까지 기다린다. */
async function accountButton() {
  return await screen.findByRole('button', { name: '내 계정' })
}

describe('ConsumerAccountMenu', () => {
  /*
   * 이 화면의 존재 이유다.
   *
   * `AppLayout`의 주 메뉴는 지금 경로가 속한 route 그룹의 shell로 정해진다.
   * 로그인 직후 도착하는 `/`는 public 그룹이라 주 메뉴에 마이페이지가 없다.
   * 계정 메뉴에도 없으면 URL을 직접 입력하는 수밖에 없어진다.
   */
  it('공용 화면에서도 계정 메뉴로 마이페이지에 갈 수 있다', async () => {
    server.use(authenticatedConsumer())
    renderMenu(ROUTES.home)

    fireEvent.click(await accountButton())

    expect(
      screen.getByRole('link', { name: '마이페이지' }),
    ).toHaveAttribute('href', ROUTES.myPage)
    expect(screen.getByRole('link', { name: '내 예약' })).toHaveAttribute(
      'href',
      ROUTES.myReservations,
    )
  })

  /* 계정 메뉴는 계정이 주제이므로 마이페이지가 첫 항목이다. 하단 탭 바는 맨 끝에 둔다. */
  it('마이페이지를 첫 항목으로 놓는다', async () => {
    server.use(authenticatedConsumer())
    renderMenu()

    fireEvent.click(await accountButton())

    const labels = screen
      .getAllByRole('link')
      .map((link) => link.textContent?.trim())
    expect(labels[0]).toBe('마이페이지')
  })

  it('펼치기 전에는 항목을 노출하지 않는다', async () => {
    server.use(authenticatedConsumer())
    renderMenu()

    const trigger = await accountButton()

    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('link', { name: '마이페이지' })).toBeNull()

    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
  })

  it('다시 누르면 접는다', async () => {
    server.use(authenticatedConsumer())
    renderMenu()

    const trigger = await accountButton()
    fireEvent.click(trigger)
    fireEvent.click(trigger)

    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('link', { name: '마이페이지' })).toBeNull()
  })

  /* 초점이 사라지면 키보드 사용자는 방금 어디에 있었는지 잃는다. */
  it('Escape로 닫으면 초점을 계정 버튼으로 돌린다', async () => {
    server.use(authenticatedConsumer())
    renderMenu()

    const trigger = await accountButton()
    fireEvent.click(trigger)
    fireEvent.keyDown(screen.getByRole('link', { name: '마이페이지' }), {
      key: 'Escape',
    })

    expect(screen.queryByRole('link', { name: '마이페이지' })).toBeNull()
    expect(trigger).toHaveFocus()
  })

  it('바깥을 누르면 닫는다', async () => {
    server.use(authenticatedConsumer())
    renderMenu()

    fireEvent.click(await accountButton())
    expect(screen.getByRole('link', { name: '마이페이지' })).toBeVisible()

    fireEvent.pointerDown(document.body)

    expect(screen.queryByRole('link', { name: '마이페이지' })).toBeNull()
  })

  it('항목을 누르면 그 경로로 이동하고 패널을 닫는다', async () => {
    server.use(authenticatedConsumer())
    renderMenu(ROUTES.home)

    fireEvent.click(await accountButton())
    fireEvent.click(screen.getByRole('link', { name: '마이페이지' }))

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(ROUTES.myPage),
    )
    expect(screen.queryByRole('link', { name: '마이페이지' })).toBeNull()
  })

  it('로그아웃하면 홈으로 보낸다', async () => {
    server.use(authenticatedConsumer(), ...signOutHandlers())
    renderMenu(ROUTES.myPage)

    fireEvent.click(await accountButton())
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(ROUTES.home),
    )
  })

  it('비로그인 상태에서는 계정 버튼 대신 로그인·회원가입을 보여 준다', async () => {
    server.use(unauthenticatedConsumer)
    renderMenu()

    expect(await screen.findByRole('link', { name: '로그인' })).toBeVisible()
    expect(screen.getByRole('link', { name: '회원가입' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '내 계정' })).toBeNull()
  })
})
