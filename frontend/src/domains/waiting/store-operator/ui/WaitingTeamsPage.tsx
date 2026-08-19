import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { SelectField } from '../../../../shared/ui/Field'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { PageHeader, useAdoptStoreFromRoute } from '../../../store/store-operator'
import { useWaitingTeams } from '../api/queries'
import { waitingErrorMessage } from '../model/errors'
import {
  TEAM_STATUSES,
  TEAM_STATUS_LABEL,
  type WaitingTeamListItem,
  type WaitingTeamStatus,
} from '../model/types'

const PAGE_SIZE = 20

/**
 * 웨이팅 FIFO 목록.
 *
 * 서버가 `(queueSequence, waitingTeamId)` 오름차순으로 정렬하고 opaque cursor를
 * 준다. 화면은 그 순서를 그대로 쓰고 클라이언트에서 다시 정렬하지 않는다. 순번을
 * 화면이 계산하면 필터·페이지 경계에서 서버와 다른 답이 나온다.
 *
 * 실시간 구독(#250)은 계약이 없다. "마지막 갱신"은 자동 갱신을 약속하지 않도록
 * 수동 새로고침과 함께 표시한다.
 */
export function WaitingTeamsPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)

  const [status, setStatus] = useState<WaitingTeamStatus | ''>('')
  /*
   * cursor 이력.
   *
   * 계약은 다음 페이지 cursor만 준다. 이전 페이지로 돌아가려면 지나온 cursor를
   * 쌓아 두는 수밖에 없다. 마지막 항목은 현재 페이지의 시작 cursor다.
   */
  const [cursors, setCursors] = useState<readonly (string | undefined)[]>([
    undefined,
  ])

  const cursor = cursors[cursors.length - 1]
  const query = { status: status === '' ? undefined : status, cursor, size: PAGE_SIZE }
  const teams = useWaitingTeams(storeId, query)

  function resetPaging() {
    setCursors([undefined])
  }

  return (
    <>
      <PageHeader
        title="웨이팅 목록"
        description="대기 순서대로 팀 상태를 확인하고 처리합니다."
        actions={
          <Button
            variant="ghost"
            loading={teams.isFetching}
            onClick={() => void teams.refetch()}
          >
            새로 고침
          </Button>
        }
      />

      <section className="mi-card">
        <div className="mi-card__body">
          <div className="op-toolbar">
            <div className="op-toolbar__filters">
              <div className="op-toolbar__field">
                <SelectField
                  label="상태"
                  value={status}
                  onChange={(event) => {
                    setStatus(event.target.value as WaitingTeamStatus | '')
                    resetPaging()
                  }}
                >
                  <option value="">전체</option>
                  {TEAM_STATUSES.map((value) => (
                    <option key={value} value={value}>
                      {TEAM_STATUS_LABEL[value]}
                    </option>
                  ))}
                </SelectField>
              </div>
            </div>
          </div>

          {teams.isPending && <Loading label="대기 목록을 불러오는 중입니다." />}

          {teams.isError && (
            <ErrorState
              error={teams.error}
              message={waitingErrorMessage(teams.error)}
              onRetry={() => void teams.refetch()}
            />
          )}

          {teams.isSuccess && (
            <>
              {teams.data.items.length === 0 ? (
                <EmptyState
                  title="표시할 대기 팀이 없습니다."
                  description="상태 조건을 바꾸거나 새로 고쳐 보세요."
                />
              ) : (
                <WaitingTable storeId={storeId} items={teams.data.items} />
              )}

              <div className="op-actions">
                <Button
                  variant="ghost"
                  disabled={cursors.length <= 1 || teams.isFetching}
                  onClick={() => setCursors((prev) => prev.slice(0, -1))}
                >
                  이전
                </Button>
                <Button
                  variant="ghost"
                  disabled={teams.data.nextCursor === null || teams.isFetching}
                  onClick={() => {
                    const next = teams.data.nextCursor
                    if (next !== null) {
                      setCursors((prev) => [...prev, next])
                    }
                  }}
                >
                  다음
                </Button>
                <span className="mi-pagination__status">
                  {`${cursors.length}번째 페이지 · ${teams.data.items.length}팀`}
                </span>
              </div>
            </>
          )}
        </div>
      </section>
    </>
  )
}

function WaitingTable({
  storeId,
  items,
}: {
  storeId: string
  items: readonly WaitingTeamListItem[]
}) {
  return (
    <div className="op-table-scroll">
      <table className="op-table">
        <caption className="visually-hidden">
          웨이팅 팀 목록. 서버가 정한 대기 순서대로 표시합니다.
        </caption>
        <thead>
          <tr>
            <th scope="col" className="op-table__numeric">
              순번
            </th>
            <th scope="col">상태</th>
            <th scope="col" className="op-table__numeric">
              인원
            </th>
            <th scope="col">등록 시각</th>
            <th scope="col">관리</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.waitingTeamId}>
              <th scope="row" className="op-table__numeric">
                {item.queueSequence}
              </th>
              <td>
                <Badge tone={statusTone(item.status)}>
                  {TEAM_STATUS_LABEL[item.status]}
                </Badge>
              </td>
              <td className="op-table__numeric">{`${item.partySize}명`}</td>
              <td>{formatDateTime(item.createdAt)}</td>
              <td>
                <Link
                  className="mi-button mi-button--ghost mi-button--sm"
                  to={fillPath(STORE_OPERATOR_PATHS.waitingTeam, {
                    storeId,
                    waitingTeamId: item.waitingTeamId,
                  })}
                  aria-label={`대기 ${item.queueSequence}번 상세`}
                >
                  상세
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * 상태 색.
 *
 * 진행 중인 대기는 긍정, 종결 실패 계열은 부정, 나머지 종결은 중립으로 묶는다.
 * 의미는 항상 뱃지 안의 문구가 전달하고 색은 보조 신호다.
 */
export function statusTone(
  status: WaitingTeamStatus,
): 'positive' | 'negative' | 'neutral' {
  switch (status) {
    case 'WAITING':
    case 'CALLED':
    case 'ARRIVED':
    case 'RESERVATION_CONVERTING':
      return 'positive'
    case 'CANCELLED':
    case 'NO_SHOW':
    case 'CLOSED_BY_STORE':
      return 'negative'
    case 'CHECKED_IN':
    case 'RESERVATION_CONVERTED':
      return 'neutral'
  }
}

export function formatDateTime(isoDateTime: string): string {
  const instant = new Date(isoDateTime)
  if (Number.isNaN(instant.getTime())) {
    return isoDateTime
  }
  // 24시간 표기를 강제한다. 일부 Node ICU 구성이 ko-KR 오전·오후를 "PM"으로 낸다.
  const formatter = new Intl.DateTimeFormat('ko-KR', {
    hourCycle: 'h23',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
  return formatter.format(instant)
}
