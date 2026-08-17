import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { ROUTES, fillPath } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { STORE_ID, authenticatedOperator } from '../../store-operator/test/handlers'
import { renderOperator } from '../../store-operator/test/renderOperator'
import {
  WAITING_IMPACT_PATH,
  WAITING_SETTINGS_PATH,
  closureJob,
  disableImpact,
  waitingSetting,
  waitingSettingsHandler,
} from '../test/handlers'
import { WaitingSettingsPage } from './WaitingSettingsPage'

function renderPage() {
  return renderOperator(<WaitingSettingsPage />, {
    route: fillPath(ROUTES.storeOperatorWaitingSettings, { storeId: STORE_ID }),
    path: ROUTES.storeOperatorWaitingSettings,
  })
}

describe('웨이팅 설정 화면', () => {
  it('조회한 설정 버전을 expectedVersion으로 보낸다', async () => {
    const bodies: unknown[] = []
    server.use(
      authenticatedOperator(),
      waitingSettingsHandler(waitingSetting({ version: 7 })),
      http.put(WAITING_SETTINGS_PATH, async ({ request }) => {
        bodies.push(await request.json())
        return successResponse(waitingSetting({ version: 8, advanceOpenMinutes: 45 }))
      }),
    )

    renderPage()
    const minutes = await screen.findByLabelText('자동 접수 선오픈')
    fireEvent.change(minutes, { target: { value: '45' } })
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toMatchObject({
      expectedVersion: 7,
      enabled: true,
      receptionMode: 'AUTO',
      advanceOpenMinutes: 45,
    })
  })

  it('version 충돌이면 입력을 지우지 않고 최신 설정을 다시 읽는다', async () => {
    let gets = 0
    server.use(
      authenticatedOperator(),
      http.get(WAITING_SETTINGS_PATH, () => {
        gets += 1
        // 두 번째 조회부터 서버가 앞서 나간 값을 준다.
        return successResponse(
          gets === 1
            ? waitingSetting({ version: 7, advanceOpenMinutes: 30 })
            : waitingSetting({ version: 9, advanceOpenMinutes: 10 }),
        )
      }),
      http.put(WAITING_SETTINGS_PATH, () =>
        errorResponse(409, 'WAITING_001', '웨이팅 설정이 변경되었습니다.'),
      ),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('자동 접수 선오픈'), {
      target: { value: '45' },
    })
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))

    expect(
      await screen.findByText(
        '조회 후 웨이팅 설정이 바뀌었습니다. 최신 설정을 다시 확인해 주세요.',
      ),
    ).toBeInTheDocument()
    // 최신 설정을 다시 읽는다.
    await waitFor(() => expect(gets).toBeGreaterThan(1))
    // 그래도 운영자가 입력한 값은 남아 있어야 한다. 지워지면 다시 입력해야 한다.
    expect(screen.getByLabelText('자동 접수 선오픈')).toHaveValue(45)
  })

  it('충돌 뒤 재저장은 다시 읽은 최신 version으로 보낸다', async () => {
    let gets = 0
    const bodies: Record<string, unknown>[] = []
    server.use(
      authenticatedOperator(),
      http.get(WAITING_SETTINGS_PATH, () => {
        gets += 1
        return successResponse(
          gets === 1 ? waitingSetting({ version: 7 }) : waitingSetting({ version: 9 }),
        )
      }),
      http.put(WAITING_SETTINGS_PATH, async ({ request }) => {
        bodies.push((await request.json()) as Record<string, unknown>)
        return bodies.length === 1
          ? errorResponse(409, 'WAITING_001', '웨이팅 설정이 변경되었습니다.')
          : successResponse(waitingSetting({ version: 10 }))
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('자동 접수 선오픈'), {
      target: { value: '45' },
    })
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))
    await screen.findByText(
      '조회 후 웨이팅 설정이 바뀌었습니다. 최신 설정을 다시 확인해 주세요.',
    )
    await waitFor(() => expect(gets).toBeGreaterThan(1))

    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))

    await waitFor(() => expect(bodies).toHaveLength(2))
    expect(bodies[0]).toMatchObject({ expectedVersion: 7 })
    // 옛 version으로 다시 보내면 같은 충돌이 반복된다.
    expect(bodies[1]).toMatchObject({ expectedVersion: 9, advanceOpenMinutes: 45 })
  })

  it('끄는 변경은 영향을 확인하기 전에는 저장하지 않는다', async () => {
    const puts: unknown[] = []
    server.use(
      authenticatedOperator(),
      waitingSettingsHandler(),
      http.get(WAITING_IMPACT_PATH, () =>
        successResponse(disableImpact({ activeTeamCount: 2 })),
      ),
      http.put(WAITING_SETTINGS_PATH, async ({ request }) => {
        puts.push(await request.json())
        return successResponse(waitingSetting({ enabled: false, receptionMode: 'PAUSED' }))
      }),
    )

    renderPage()
    fireEvent.click(
      await screen.findByLabelText('이 매장에서 웨이팅을 사용합니다'),
    )
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))

    expect(
      await screen.findByText('현재 2팀이 대기 중입니다.'),
    ).toBeInTheDocument()
    // 확인 단계에서는 아직 저장 요청이 나가지 않는다.
    expect(puts).toHaveLength(0)
  })

  it('활성 팀 처리 방법을 disableAction으로 보낸다', async () => {
    const puts: Record<string, unknown>[] = []
    server.use(
      authenticatedOperator(),
      waitingSettingsHandler(),
      http.get(WAITING_IMPACT_PATH, () =>
        successResponse(disableImpact({ activeTeamCount: 2 })),
      ),
      http.put(WAITING_SETTINGS_PATH, async ({ request }) => {
        puts.push((await request.json()) as Record<string, unknown>)
        return successResponse(closureJob())
      }),
    )

    renderPage()
    fireEvent.click(
      await screen.findByLabelText('이 매장에서 웨이팅을 사용합니다'),
    )
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))
    fireEvent.click(
      await screen.findByRole('button', { name: '대기 2팀 일괄 종결하고 끄기' }),
    )

    await waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0]).toMatchObject({
      enabled: false,
      receptionMode: 'PAUSED',
      disableAction: 'CLOSE_ACTIVE_TEAMS',
    })
  })

  it('202 종결 작업 응답은 진행 상태로 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      waitingSettingsHandler(),
      http.get(WAITING_IMPACT_PATH, () =>
        successResponse(disableImpact({ activeTeamCount: 2 })),
      ),
      http.put(WAITING_SETTINGS_PATH, () =>
        successResponse(
          closureJob({ status: 'PROCESSING', totalTeamCount: 2, completedTeamCount: 1 }),
        ),
      ),
    )

    renderPage()
    fireEvent.click(
      await screen.findByLabelText('이 매장에서 웨이팅을 사용합니다'),
    )
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))
    fireEvent.click(
      await screen.findByRole('button', { name: '대기 2팀 일괄 종결하고 끄기' }),
    )

    expect(
      await screen.findByText('대기 팀 일괄 종결을 시작했습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/상태 처리 중 · 대상 2팀 · 완료 1팀 · 실패 0팀/),
    ).toBeInTheDocument()
  })

  it('활성 팀이 없으면 일괄 종결을 제시하지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      waitingSettingsHandler(),
      http.get(WAITING_IMPACT_PATH, () =>
        successResponse(disableImpact({ activeTeamCount: 0 })),
      ),
    )

    renderPage()
    fireEvent.click(
      await screen.findByLabelText('이 매장에서 웨이팅을 사용합니다'),
    )
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))

    expect(
      await screen.findByText('영향을 받는 대기 팀이 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /일괄 종결/ }),
    ).not.toBeInTheDocument()
  })

  it('입점 검증 거절은 서버 코드 그대로 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(WAITING_SETTINGS_PATH, () =>
        errorResponse(409, 'STORE_007', '현재 입점 검증 상태에서 운영할 수 없습니다.'),
      ),
    )

    renderPage()

    expect(
      await screen.findByText(
        '입점 검증이 승인된 매장만 웨이팅을 운영할 수 있습니다.',
      ),
    ).toBeInTheDocument()
  })
})
