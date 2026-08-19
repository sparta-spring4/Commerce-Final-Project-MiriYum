import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { STORE_ID, authenticatedOperator } from '../../../store/store-operator/test/handlers'
import { renderOperator } from '../../../store/store-operator/test/renderOperator'
import {
  WAITING_TEAMS_PATH,
  teamDetail,
  teamItem,
  teamPage,
  waitingCommandPath,
  waitingTeamPath,
} from '../test/handlers'
import { WaitingTeamDetailPage } from './WaitingTeamDetailPage'

const TEAM_ID = '11'

function renderPage() {
  return renderOperator(<WaitingTeamDetailPage />, {
    route: fillPath(STORE_OPERATOR_PATHS.waitingTeam, {
      storeId: STORE_ID,
      waitingTeamId: TEAM_ID,
    }),
    path: STORE_OPERATOR_PATHS.waitingTeam,
  })
}

/**
 * 상태 필터를 계약대로 지키는 목록 핸들러.
 *
 * 화면은 이 endpoint를 두 번 쓴다 — 대기 중 선두 한 건과 호출된 팀 존재 여부.
 * 핸들러가 `status`를 무시하면 두 조회가 같은 답을 받아, 실제 서버에서는
 * 나뉘는 두 조건이 테스트에서 하나로 뭉개진다.
 */
function teamsHandler({
  waiting = [] as readonly ReturnType<typeof teamItem>[],
  called = [] as readonly ReturnType<typeof teamItem>[],
} = {}) {
  return http.get(WAITING_TEAMS_PATH, ({ request }) => {
    const status = new URL(request.url).searchParams.get('status')
    if (status === 'WAITING') return successResponse(teamPage(waiting))
    if (status === 'CALLED') return successResponse(teamPage(called))
    return successResponse(teamPage([...waiting, ...called]))
  })
}

/** 이 팀이 FIFO 선두이고 호출된 팀이 없는 상태. */
function headIsThisTeam() {
  return teamsHandler({ waiting: [teamItem({ waitingTeamId: TEAM_ID })] })
}

/** 앞에 다른 대기 팀이 있는 상태. */
function headIsAnotherTeam() {
  return teamsHandler({
    waiting: [teamItem({ waitingTeamId: '10', queueSequence: 1 })],
  })
}

describe('웨이팅 팀 상세 화면', () => {
  it('대기 중 선두 팀에는 호출과 취소만 연다', async () => {
    server.use(
      authenticatedOperator(),
      headIsThisTeam(),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'WAITING' })),
      ),
    )

    renderPage()

    expect(await screen.findByRole('button', { name: '호출' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '취소' })).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '입장 처리' }),
    ).not.toBeInTheDocument()
  })

  it('선두가 아니면 호출을 누를 수 없다', async () => {
    server.use(
      authenticatedOperator(),
      headIsAnotherTeam(),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'WAITING', queueSequence: 5 })),
      ),
    )

    renderPage()

    expect(await screen.findByRole('button', { name: '호출' })).toBeDisabled()
    expect(
      screen.getByText(/대기 순서상 맨 앞 팀만 호출할 수 있습니다/),
    ).toBeInTheDocument()
  })

  it('호출된 팀에는 도착 확인을 연다', async () => {
    server.use(
      authenticatedOperator(),
      headIsAnotherTeam(),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'CALLED' })),
      ),
    )

    renderPage()

    expect(
      await screen.findByRole('button', { name: '도착 확인' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '호출' })).not.toBeInTheDocument()
  })

  it('종결 상태에는 어떤 명령도 열지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      headIsAnotherTeam(),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'CHECKED_IN' })),
      ),
    )

    renderPage()

    expect(
      await screen.findByText('이미 입장 완료 상태입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '취소' })).not.toBeInTheDocument()
  })

  it('예약 전환 중에는 취소만 허용한다', async () => {
    server.use(
      authenticatedOperator(),
      headIsAnotherTeam(),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'RESERVATION_CONVERTING' })),
      ),
    )

    renderPage()

    expect(await screen.findByRole('button', { name: '취소' })).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '입장 처리' }),
    ).not.toBeInTheDocument()
  })

  it('명령에 조회한 원장 version과 멱등 키를 보낸다', async () => {
    const requests: { body: unknown; key: string | null }[] = []
    server.use(
      authenticatedOperator(),
      headIsThisTeam(),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'WAITING', version: 4 })),
      ),
      http.post(waitingCommandPath(TEAM_ID, 'calls'), async ({ request }) => {
        requests.push({
          body: await request.json(),
          key: request.headers.get('Idempotency-Key'),
        })
        return successResponse(teamDetail({ status: 'CALLED', version: 5 }))
      }),
    )

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '호출' }))

    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0].body).toEqual({ expectedVersion: 4 })
    expect(requests[0].key).toMatch(/^[0-9a-f-]{36}$/i)
  })

  it('같은 명령을 재시도하는 동안 멱등 키를 유지한다', async () => {
    const keys: (string | null)[] = []
    server.use(
      authenticatedOperator(),
      headIsThisTeam(),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'WAITING', version: 4 })),
      ),
      http.post(waitingCommandPath(TEAM_ID, 'calls'), ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return errorResponse(503, 'COMMON_012', '일시적으로 이용할 수 없습니다.')
      }),
    )

    renderPage()
    const call = await screen.findByRole('button', { name: '호출' })
    fireEvent.click(call)
    await screen.findByText(
      '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
    )
    fireEvent.click(screen.getByRole('button', { name: '호출' }))

    await waitFor(() => expect(keys).toHaveLength(2))
    // 실패한 같은 의도의 재시도다. 키가 바뀌면 서버가 두 건으로 본다.
    expect(keys[0]).toBe(keys[1])
  })

  it('version 충돌은 최신 상태를 다시 읽도록 안내한다', async () => {
    let detailReads = 0
    const requests: { body: unknown; key: string | null }[] = []
    server.use(
      authenticatedOperator(),
      headIsThisTeam(),
      http.get(waitingTeamPath(TEAM_ID), () => {
        detailReads += 1
        return successResponse(
          teamDetail({
            status: 'WAITING',
            version: detailReads === 1 ? 4 : 5,
          }),
        )
      }),
      http.post(waitingCommandPath(TEAM_ID, 'calls'), async ({ request }) => {
        requests.push({
          body: await request.json(),
          key: request.headers.get('Idempotency-Key'),
        })
        return errorResponse(409, 'WAITING_005', '웨이팅 팀이 변경되었습니다.')
      }),
    )

    renderPage()
    const before = detailReads
    fireEvent.click(await screen.findByRole('button', { name: '호출' }))

    expect(
      await screen.findByText(
        '조회 후 이 팀의 상태가 바뀌었습니다. 최신 상태를 다시 확인해 주세요.',
      ),
    ).toBeInTheDocument()
    // 성공을 낙관 확정하지 않고 서버를 다시 읽는다.
    await waitFor(() => expect(detailReads).toBeGreaterThan(before))

    fireEvent.click(screen.getByRole('button', { name: '호출' }))
    await waitFor(() => expect(requests).toHaveLength(2))
    expect(requests[0].body).toEqual({ expectedVersion: 4 })
    expect(requests[1].body).toEqual({ expectedVersion: 5 })
    // endpoint·명령·본문이 같은 논리적 요청일 때만 키를 재사용한다.
    expect(requests[1].key).not.toBe(requests[0].key)
  })

  it('FIFO 선두가 아니라는 서버 거절을 그대로 옮긴다', async () => {
    server.use(
      authenticatedOperator(),
      headIsThisTeam(),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'WAITING', version: 4 })),
      ),
      http.post(waitingCommandPath(TEAM_ID, 'calls'), () =>
        errorResponse(409, 'WAITING_007', 'FIFO 선두 웨이팅 팀만 호출할 수 있습니다.'),
      ),
    )

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '호출' }))

    expect(
      await screen.findByText('대기 순서상 맨 앞 팀만 호출할 수 있습니다.'),
    ).toBeInTheDocument()
  })

  it('이미 호출한 팀이 있으면 선두여도 호출을 열지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      // 서버는 선두 판정 전에 CALLED 존재부터 보고 WAITING_007로 막는다.
      teamsHandler({
        waiting: [teamItem({ waitingTeamId: TEAM_ID })],
        called: [teamItem({ waitingTeamId: '9', status: 'CALLED', queueSequence: 1 })],
      }),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'WAITING' })),
      ),
    )

    renderPage()

    expect(await screen.findByRole('button', { name: '호출' })).toBeDisabled()
    expect(
      screen.getByText(/이미 호출한 팀이 있습니다/),
    ).toBeInTheDocument()
  })

  it('호출된 팀 조회가 실패하면 호출을 열지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(WAITING_TEAMS_PATH, ({ request }) => {
        const status = new URL(request.url).searchParams.get('status')
        if (status === 'CALLED') {
          return errorResponse(503, 'COMMON_012', '일시적으로 이용할 수 없습니다.')
        }
        return successResponse(teamPage([teamItem({ waitingTeamId: TEAM_ID })]))
      }),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'WAITING' })),
      ),
    )

    renderPage()

    // 모르는 상태에서 열면 서버가 거절할 행동을 약속하게 된다.
    expect(await screen.findByRole('button', { name: '호출' })).toBeDisabled()
  })

  it('계약에 없는 값을 지어내지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      headIsAnotherTeam(),
      http.get(waitingTeamPath(TEAM_ID), () =>
        successResponse(teamDetail({ status: 'WAITING' })),
      ),
    )

    renderPage()
    await screen.findByText('인원')

    // 예상 대기시간·고객 연락처는 운영자 상세 계약에 없다.
    expect(screen.queryByText(/예상 대기/)).not.toBeInTheDocument()
    expect(screen.queryByText(/연락처/)).not.toBeInTheDocument()
  })
})
