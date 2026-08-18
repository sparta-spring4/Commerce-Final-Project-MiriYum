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
  waitingClosureJobPath,
  waitingSetting,
  waitingSettingsHandler,
} from '../test/handlers'
import { CLOSURE_JOB_POLL_MS } from '../api/queries'
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
    const keys: (string | null)[] = []
    server.use(
      authenticatedOperator(),
      http.get(WAITING_SETTINGS_PATH, () => {
        gets += 1
        return successResponse(
          gets === 1 ? waitingSetting({ version: 7 }) : waitingSetting({ version: 9 }),
        )
      }),
      http.put(WAITING_SETTINGS_PATH, async ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
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
    // expectedVersion까지 요청 지문의 일부다. 본문이 달라졌는데 같은 키를 쓰면
    // 서버가 앞선 충돌 응답을 재생한다.
    expect(keys[1]).not.toBe(keys[0])
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

  /** 끄기를 확정해 202 종결 작업을 시작시킨다. */
  async function startClosure() {
    fireEvent.click(
      await screen.findByLabelText('이 매장에서 웨이팅을 사용합니다'),
    )
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))
    fireEvent.click(
      await screen.findByRole('button', { name: '대기 2팀 일괄 종결하고 끄기' }),
    )
  }

  /** 끄기 확인까지 필요한 공통 핸들러. 202 본문만 테스트마다 바꾼다. */
  function disableFlow(job = closureJob()) {
    return [
      authenticatedOperator(),
      waitingSettingsHandler(),
      http.get(WAITING_IMPACT_PATH, () =>
        successResponse(disableImpact({ activeTeamCount: 2 })),
      ),
      http.put(WAITING_SETTINGS_PATH, () => successResponse(job)),
    ]
  }

  it('202 종결 작업 응답은 진행 상태로 안내한다', async () => {
    const started = closureJob({
      status: 'PROCESSING',
      totalTeamCount: 2,
      completedTeamCount: 1,
    })
    server.use(
      ...disableFlow(started),
      // 조회가 아직 같은 상태를 준다. 202 본문만으로 끝내지 않는다는 전제다.
      http.get(waitingClosureJobPath(started.jobId), () =>
        successResponse(started),
      ),
    )

    renderPage()
    await startClosure()

    expect(
      await screen.findByText('대기 팀 일괄 종결을 시작했습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText(
        /상태 처리 중 · 대상 2팀 · 완료 1팀 · 실패 0팀 · 대사 필요 0팀/,
      ),
    ).toBeInTheDocument()
  })

  it('202 이후 서버가 완료로 바꾸면 화면도 완료로 바뀐다', async () => {
    const started = closureJob({
      status: 'PENDING',
      totalTeamCount: 2,
      completedTeamCount: 0,
    })
    server.use(
      ...disableFlow(started),
      http.get(waitingClosureJobPath(started.jobId), () =>
        successResponse(
          closureJob({
            status: 'COMPLETED',
            totalTeamCount: 2,
            completedTeamCount: 2,
          }),
        ),
      ),
    )

    renderPage()
    await startClosure()

    // 202 본문에 멈추지 않고 jobId로 다시 읽어 종결까지 따라간다.
    expect(
      await screen.findByText('대기 팀 일괄 종결을 마쳤습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/상태 완료 · 대상 2팀 · 완료 2팀/),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('대기 팀 일괄 종결을 시작했습니다.'),
    ).not.toBeInTheDocument()
  })

  it('종결하지 못한 팀이 남으면 확인이 필요하다고 알린다', async () => {
    const started = closureJob({ status: 'PROCESSING', totalTeamCount: 3 })
    server.use(
      ...disableFlow(started),
      http.get(waitingClosureJobPath(started.jobId), () =>
        successResponse(
          closureJob({
            status: 'RECONCILIATION_REQUIRED',
            totalTeamCount: 3,
            completedTeamCount: 1,
            failedTeamCount: 1,
            reconciliationRequiredTeamCount: 1,
          }),
        ),
      ),
    )

    renderPage()
    await startClosure()

    expect(
      await screen.findByText('일괄 종결이 끝났지만 확인이 필요합니다.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/완료 1팀 · 실패 1팀 · 대사 필요 1팀/),
    ).toBeInTheDocument()
  })

  it('종결 작업이 끝나면 더 읽지 않는다', async () => {
    let reads = 0
    const started = closureJob({ status: 'PROCESSING' })
    server.use(
      ...disableFlow(started),
      http.get(waitingClosureJobPath(started.jobId), () => {
        reads += 1
        return successResponse(closureJob({ status: 'COMPLETED' }))
      }),
    )

    renderPage()
    await startClosure()
    await screen.findByText('대기 팀 일괄 종결을 마쳤습니다.')

    const settled = reads
    // 종결 상태에서는 백엔드가 값을 더 바꾸지 않는다. 폴링이 계속되면 화면을
    // 열어 둔 동안 같은 응답만 반복해서 받는다.
    await new Promise((resolve) => setTimeout(resolve, CLOSURE_JOB_POLL_MS + 300))
    expect(reads).toBe(settled)
  })

  it('종결 작업 조회가 실패하면 진행 스냅샷 대신 오류와 수동 재시도를 보여 준다', async () => {
    let reads = 0
    const started = closureJob({ status: 'PENDING' })
    server.use(
      ...disableFlow(started),
      http.get(waitingClosureJobPath(started.jobId), () => {
        reads += 1
        return reads === 1
          ? errorResponse(503, 'COMMON_012', '일시적으로 이용할 수 없습니다.')
          : successResponse(closureJob({ status: 'COMPLETED' }))
      }),
    )

    renderPage()
    await startClosure()

    expect(
      await screen.findByText(
        '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('대기 팀 일괄 종결을 시작했습니다.'),
    ).not.toBeInTheDocument()

    const failedReads = reads
    await new Promise((resolve) => setTimeout(resolve, CLOSURE_JOB_POLL_MS + 300))
    expect(reads).toBe(failedReads)

    fireEvent.click(screen.getByRole('button', { name: '상태 다시 확인' }))
    expect(
      await screen.findByText('대기 팀 일괄 종결을 마쳤습니다.'),
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
