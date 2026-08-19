import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { Link, useParams } from 'react-router'
import { Badge } from '../../../../shared/ui/Badge'
import { ErrorState, Loading } from '../../../../shared/ui/Feedback'
import {
  PageHeader,
  SectionCard,
  SummaryList,
  useAdoptStoreFromRoute,
} from '../../../store/store-operator'
import { useWaitingTeam, useWaitingTeams } from '../api/queries'
import { waitingErrorMessage } from '../model/errors'
import { TEAM_STATUS_LABEL, type WaitingTeamDetail } from '../model/types'
import { WaitingCommandPanel } from './WaitingCommandPanel'
import { formatDateTime, statusTone } from './WaitingTeamsPage'

/** 선두·호출 여부 판정에는 존재만 알면 된다. 목록을 더 읽지 않는다. */
const PROBE_PAGE_SIZE = 1

/**
 * 웨이팅 팀 상세와 처리.
 *
 * 표시하는 값은 계약이 운영자에게 열어 준 것만이다. `WaitingTeamDetail`에는
 * 고객 이름·연락처가 없고, 예상 대기시간도 없다. 없는 필드를 만들어 보여 주지
 * 않는다.
 */
export function WaitingTeamDetailPage() {
  const { storeId = '', waitingTeamId = '' } = useParams<{
    storeId: string
    waitingTeamId: string
  }>()
  useAdoptStoreFromRoute(storeId)

  const query = useWaitingTeam(storeId, waitingTeamId)

  /*
   * 호출 가능 여부를 판정할 FIFO 선두.
   *
   * 상세 응답만으로는 이 팀이 선두인지 알 수 없다. 대기 중 첫 팀 한 건만 따로
   * 조회해 서버가 정한 순서를 그대로 받는다. 화면이 순번을 계산하지 않는다.
   */
  const head = useWaitingTeams(storeId, {
    status: 'WAITING',
    size: PROBE_PAGE_SIZE,
  })
  const headTeamId = head.data?.items[0]?.waitingTeamId ?? null

  /*
   * 이미 호출된 팀이 있는지.
   *
   * 서버는 선두 판정 전에 매장·영업일의 `CALLED` 존재부터 확인하고, 하나라도
   * 있으면 다음 호출을 `WAITING_007`로 막는다(동시 호출 1팀). 상세 계약에
   * "현재 호출된 팀"이 없으므로 목록을 상태로 걸러 존재만 확인한다.
   *
   * 이걸 빼면 선두 팀에서 호출 버튼이 열리지만 누르는 순간 반드시 거절된다.
   */
  const called = useWaitingTeams(storeId, {
    status: 'CALLED',
    size: PROBE_PAGE_SIZE,
  })

  return (
    <>
      <PageHeader
        title="대기 팀 상세"
        description="대기 순서와 상태를 확인하고 처리합니다."
        actions={
          <Link
            className="mi-button mi-button--ghost"
            to={fillPath(STORE_OPERATOR_PATHS.waitingTeams, { storeId })}
          >
            웨이팅 목록
          </Link>
        }
      />

      {query.isPending && <Loading label="대기 팀을 불러오는 중입니다." />}

      {query.isError && (
        <ErrorState
          error={query.error}
          message={waitingErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      )}

      {query.isSuccess && (
        <TeamFacts
          storeId={storeId}
          team={query.data}
          // 두 조회가 끝나기 전에는 호출을 열지 않는다. 모르는 상태에서 열면
          // 서버가 WAITING_007로 거절할 행동을 약속하게 된다.
          isQueueHead={
            head.isSuccess && headTeamId === query.data.waitingTeamId
          }
          // 알 수 없는 동안은 "있다"로 본다. 없다고 가정하면 버튼이 먼저 열린다.
          hasCalledTeam={!called.isSuccess || called.data.items.length > 0}
        />
      )}
    </>
  )
}

function TeamFacts({
  storeId,
  team,
  isQueueHead,
  hasCalledTeam,
}: {
  storeId: string
  team: WaitingTeamDetail
  isQueueHead: boolean
  hasCalledTeam: boolean
}) {
  return (
    <div className="op-stack">
      <SectionCard
        title={`대기 ${team.queueSequence}번`}
        icon="users"
        actions={
          <Badge tone={statusTone(team.status)}>
            {TEAM_STATUS_LABEL[team.status]}
          </Badge>
        }
      >
        <SummaryList
          items={[
            { term: '인원', value: `${team.partySize}명` },
            { term: '등록', value: formatDateTime(team.createdAt) },
            {
              term: '호출',
              value:
                team.calledAt == null ? '—' : formatDateTime(team.calledAt),
            },
            {
              term: '도착',
              value:
                team.arrivedAt == null ? '—' : formatDateTime(team.arrivedAt),
            },
            {
              term: '입장',
              value:
                team.checkedInAt == null
                  ? '—'
                  : formatDateTime(team.checkedInAt),
            },
            {
              term: '취소',
              value:
                team.cancelledAt == null
                  ? '—'
                  : formatDateTime(team.cancelledAt),
            },
          ]}
        />
        <p className="op-section__hint">
          {`원장 버전 ${team.version} · 처리 요청에 이 버전을 함께 보냅니다.`}
        </p>
      </SectionCard>

      <WaitingCommandPanel
        storeId={storeId}
        team={team}
        isQueueHead={isQueueHead}
        hasCalledTeam={hasCalledTeam}
      />
    </div>
  )
}
