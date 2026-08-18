import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { ROUTES, fillPath } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { STORE_ID, authenticatedOperator } from '../../store-operator/test/handlers'
import { renderOperator } from '../../store-operator/test/renderOperator'
import { WAITING_TEAMS_PATH, teamItem, teamPage } from '../test/handlers'
import { WaitingTeamsPage } from './WaitingTeamsPage'

function renderPage() {
  return renderOperator(<WaitingTeamsPage />, {
    route: fillPath(ROUTES.storeOperatorWaitingTeams, { storeId: STORE_ID }),
    path: ROUTES.storeOperatorWaitingTeams,
  })
}

describe('웨이팅 목록 화면', () => {
  it('서버가 준 순서를 그대로 표시한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(WAITING_TEAMS_PATH, () =>
        successResponse(
          teamPage([
            // 서버가 정한 FIFO 순서. 화면이 다시 정렬하면 이 순서가 깨진다.
            teamItem({ waitingTeamId: '31', queueSequence: 12, partySize: 4 }),
            teamItem({ waitingTeamId: '32', queueSequence: 13, partySize: 2 }),
            teamItem({ waitingTeamId: '33', queueSequence: 14, partySize: 6 }),
          ]),
        ),
      ),
    )

    renderPage()
    await screen.findByText('12')

    const rows = screen.getAllByRole('rowheader').map((h) => h.textContent)
    expect(rows).toEqual(['12', '13', '14'])
  })

  it('다음 페이지는 서버 cursor를 그대로 보낸다', async () => {
    const queries: string[] = []
    server.use(
      authenticatedOperator(),
      http.get(WAITING_TEAMS_PATH, ({ request }) => {
        const search = new URL(request.url).searchParams
        queries.push(search.toString())
        return successResponse(
          search.get('cursor') === null
            ? teamPage([teamItem({ waitingTeamId: '41', queueSequence: 1 })], 'CURSOR_A')
            : teamPage([teamItem({ waitingTeamId: '42', queueSequence: 2 })], null),
        )
      }),
    )

    renderPage()
    await screen.findByText('1')
    fireEvent.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() => expect(queries).toHaveLength(2))
    expect(queries[0]).not.toContain('cursor=')
    expect(queries[1]).toContain('cursor=CURSOR_A')
  })

  it('마지막 페이지에서는 다음으로 넘어가지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(WAITING_TEAMS_PATH, () =>
        successResponse(teamPage([teamItem()], null)),
      ),
    )

    renderPage()
    await screen.findByText('1')

    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled()
  })

  it('상태 필터를 서버 조건으로 보내고 페이지를 처음으로 되돌린다', async () => {
    const queries: string[] = []
    server.use(
      authenticatedOperator(),
      http.get(WAITING_TEAMS_PATH, ({ request }) => {
        queries.push(new URL(request.url).search)
        return successResponse(
          teamPage([teamItem({ status: 'CALLED', queueSequence: 5 })], 'CURSOR_B'),
        )
      }),
    )

    renderPage()
    await screen.findByText('5')
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    await waitFor(() => expect(queries).toHaveLength(2))

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'CALLED' },
    })

    await waitFor(() => expect(queries).toHaveLength(3))
    // 필터가 바뀌면 이전 cursor는 의미가 없다. 첫 페이지부터 다시 읽는다.
    expect(queries[2]).toContain('status=CALLED')
    expect(queries[2]).not.toContain('cursor=')
  })

  it('빈 목록과 조회 실패를 구분해 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(WAITING_TEAMS_PATH, () => successResponse(teamPage([], null))),
    )

    renderPage()

    expect(
      await screen.findByText('표시할 대기 팀이 없습니다.'),
    ).toBeInTheDocument()
  })

  it('권한 거부는 서버 코드 그대로 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(WAITING_TEAMS_PATH, () =>
        errorResponse(403, 'STORE_003', '매장 접근 권한이 없습니다.'),
      ),
    )

    renderPage()

    expect(
      await screen.findByText('이 매장의 대표 운영자가 아닙니다.'),
    ).toBeInTheDocument()
  })

  it('상세 링크는 서버가 준 팀 ID를 쓴다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(WAITING_TEAMS_PATH, () =>
        successResponse(
          teamPage([teamItem({ waitingTeamId: '77', queueSequence: 9 })]),
        ),
      ),
    )

    renderPage()
    const row = (await screen.findByRole('rowheader', { name: '9' })).closest('tr')
    expect(row).not.toBeNull()

    expect(
      within(row as HTMLElement).getByRole('link', { name: '대기 9번 상세' }),
    ).toHaveAttribute(
      'href',
      fillPath(ROUTES.storeOperatorWaitingTeam, {
        storeId: STORE_ID,
        waitingTeamId: '77',
      }),
    )
  })
})
