import type { WaitingTeamStatus } from './types'

/**
 * 운영자가 실행할 수 있는 웨이팅 명령.
 *
 * 이름은 계약의 하위 리소스와 같다. 화면이 부르는 이름과 실제 endpoint가
 * 갈라지면 어느 쪽이 옳은지 코드에서 알 수 없다.
 */
export type WaitingCommand = 'call' | 'arrive' | 'check-in' | 'cancel'

export const COMMAND_LABEL: Record<WaitingCommand, string> = {
  call: '호출',
  arrive: '도착 확인',
  'check-in': '입장 처리',
  cancel: '취소',
}

/**
 * 상태별로 계약이 허용하는 명령.
 *
 * 서버 규칙을 화면이 두 번째로 적어 두는 자리이므로, 넓게 잡지 않고 좁게 잡는다.
 * 열어 둔 버튼이 거절되면 운영자에게 "눌러도 되는 행동"을 잘못 약속한 것이 된다.
 * 반대로 좁게 잡아 실제로는 가능한 명령을 감추면 서버가 거절하지 않으므로 화면만
 * 고치면 된다.
 *
 * `RESERVATION_CONVERTING`은 비종결 상태지만 계약이 취소만 허용한다고 명시한다.
 * 나머지 종결 상태는 어떤 명령도 받지 않는다.
 */
const ALLOWED: Record<WaitingTeamStatus, readonly WaitingCommand[]> = {
  WAITING: ['call', 'cancel'],
  CALLED: ['arrive', 'cancel'],
  ARRIVED: ['check-in', 'cancel'],
  RESERVATION_CONVERTING: ['cancel'],
  CHECKED_IN: [],
  CANCELLED: [],
  NO_SHOW: [],
  CLOSED_BY_STORE: [],
  RESERVATION_CONVERTED: [],
}

export function allowedCommands(
  status: WaitingTeamStatus,
): readonly WaitingCommand[] {
  return ALLOWED[status]
}

/** 더 이상 어떤 명령도 받지 않는 상태인지. 안내 문구를 가르는 데 쓴다. */
export function isTerminal(status: WaitingTeamStatus): boolean {
  return ALLOWED[status].length === 0
}

/**
 * 호출은 FIFO 선두만 가능하다.
 *
 * 계약이 `WAITING_007`로 거절하므로 화면도 선두가 아닌 팀에는 호출을 열지 않는다.
 * 선두 판정은 서버가 준 목록 순서를 그대로 쓴다. 클라이언트가 순번을 다시 계산하면
 * 필터·페이지 경계에서 서버와 다른 답이 나온다.
 */
export function isQueueHead(
  waitingTeamId: string,
  headTeamId: string | null,
): boolean {
  return headTeamId !== null && headTeamId === waitingTeamId
}
