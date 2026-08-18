import { useMemo, useState } from 'react'
import { startIdempotentAttempt } from '../../../shared/api/idempotencyKey'
import { Button } from '../../../shared/ui/Button'
import { Alert } from '../../../shared/ui/Feedback'
import { SectionCard } from '../../store-operator'
import { useWaitingTeamCommand } from '../api/queries'
import { waitingErrorMessage } from '../model/errors'
import {
  COMMAND_LABEL,
  allowedCommands,
  isTerminal,
  type WaitingCommand,
} from '../model/transitions'
import { TEAM_STATUS_LABEL, type WaitingTeamDetail } from '../model/types'

/**
 * 팀 상태 전이 명령.
 *
 * 현재 상태가 허용하는 명령만 연다. 서버가 거절할 행동을 버튼으로 열어 두면
 * 운영자에게 할 수 있다고 잘못 약속하게 된다.
 *
 * 호출은 계약이 FIFO 선두만 허용한다(`WAITING_007`). 서버는 세 가지를 함께
 * 본다 — 대기 중 상태일 것, 이미 호출된 팀이 없을 것, 대기 중 팀의 선두일 것.
 * 화면도 같은 세 조건으로 버튼을 연다. 하나라도 빠뜨리면 눌러도 반드시 거절될
 * 행동을 열어 두게 된다. 판정 근거는 모두 목록이 준 순서이고 화면이 순번을
 * 다시 계산하지 않는다.
 *
 * 성공을 낙관 확정하지 않는다. 응답이 와도 화면 상태를 직접 고치지 않고 서버를
 * 다시 조회해 수렴시킨다(`useWaitingTeamCommand`의 `onSettled`).
 */
export function WaitingCommandPanel({
  storeId,
  team,
  isQueueHead,
  hasCalledTeam,
}: {
  storeId: string
  team: WaitingTeamDetail
  /** FIFO 선두 여부. 알 수 없으면 호출을 열지 않는다. */
  isQueueHead: boolean
  /**
   * 이미 호출된 팀이 있는지. 알 수 없으면 있다고 본다.
   *
   * 서버는 매장·영업일에 `CALLED` 팀이 하나라도 있으면 다음 호출을 막는다.
   * 도착 확인까지 끝난 팀은 막지 않으므로 `ARRIVED`는 여기 포함하지 않는다.
   */
  hasCalledTeam: boolean
}) {
  const command = useWaitingTeamCommand(storeId, team.waitingTeamId)
  const attempt = useMemo(startIdempotentAttempt, [])
  const [error, setError] = useState<string | null>(null)
  const [running, setRunning] = useState<WaitingCommand | null>(null)

  const commands = allowedCommands(team.status)
  const canCall = isQueueHead && !hasCalledTeam

  if (isTerminal(team.status)) {
    return (
      <SectionCard title="팀 처리" icon="check">
        <Alert
          tone="info"
          title={`이미 ${TEAM_STATUS_LABEL[team.status]} 상태입니다.`}
        >
          <p>처리한 팀은 이 화면에서 되돌리지 않습니다.</p>
        </Alert>
      </SectionCard>
    )
  }

  async function run(next: WaitingCommand) {
    setError(null)
    setRunning(next)
    try {
      await command.mutateAsync({
        command: next,
        expectedVersion: team.version,
        idempotencyKey: attempt.current,
      })
      /*
       * 다음 명령은 다른 의도다. 같은 키를 유지하면 서버가 앞선 결과를 재생한다.
       * 반대로 같은 명령을 다시 눌러 재시도하는 동안에는 키를 유지해야 하므로,
       * 성공한 뒤에만 새로 발급한다.
       */
      attempt.renew()
    } catch (cause) {
      setError(waitingErrorMessage(cause))
    } finally {
      setRunning(null)
    }
  }

  return (
    <SectionCard title="팀 처리" icon="check">
      {error !== null && <Alert tone="error" title={error} />}

      <div className="op-actions">
        {commands.map((name) => {
          // 호출만 순서 조건이 붙는다. 나머지는 상태만으로 결정된다.
          const blocked = name === 'call' && !canCall
          return (
            <Button
              key={name}
              variant={name === 'cancel' ? 'danger' : 'primary'}
              disabled={blocked || command.isPending}
              loading={running === name}
              onClick={() => void run(name)}
            >
              {COMMAND_LABEL[name]}
            </Button>
          )
        })}
      </div>

      {commands.includes('call') && !canCall && (
        <p className="op-section__hint">
          {hasCalledTeam
            ? '이미 호출한 팀이 있습니다. 그 팀의 도착을 확인한 뒤 다음 팀을 호출할 수 있습니다.'
            : '대기 순서상 맨 앞 팀만 호출할 수 있습니다. 목록에서 앞 팀을 먼저 처리해 주세요.'}
        </p>
      )}
    </SectionCard>
  )
}
