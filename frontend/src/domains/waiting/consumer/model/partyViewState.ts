/**
 * 소비자 웨이팅 일행 화면의 UI 전용 뷰 모델.
 *
 * 이 파일은 API를 호출하지 않고, 멱등 키나 `expectedVersion`도 다루지 않는다.
 * 화면이 표현해야 하는 상태의 이름과 안내 문구, 그리고 계약이 이미 정한 판정을
 * 옮겨 담은 파생 함수만 소유한다. 실제 값은 현재 웨이팅 snapshot과 mutation
 * 응답을 가진 컨테이너가 props로 내려 준다.
 *
 * 상태 이름은 활성 Waiting OpenAPI와 `docs/specs/waiting/spec.md`가 선언한
 * 어휘에 맞춘다. 화면이 자기 이름을 새로 만들면 계약이 바뀔 때 어긋난 안내를
 * 하게 된다.
 */
import type { components } from '../../../../shared/api/generated/waiting'

export type WaitingTeamStatus = components['schemas']['WaitingTeamStatus']
export type WaitingPartyMember = components['schemas']['WaitingPartyMember']
export type WaitingTransferOffer = components['schemas']['WaitingTransferOffer']
export type WaitingPartyRole = WaitingPartyMember['role']

/**
 * 초대 코드 발급 상태.
 *
 * `issuedWithoutCode`는 같은 멱등 키 replay로 초대 metadata만 돌아온 상태다.
 * 계약이 원문 코드를 다시 만들어 주지 않으므로 `issued`와 한 이름으로 합치지
 * 않는다. 합치면 코드 자리가 빈 채로 "복사하세요"라고 말하게 된다.
 */
export type WaitingInvitationView =
  | 'none'
  | 'issuing'
  | 'issued'
  | 'issuedWithoutCode'
  | 'expired'
  | 'revoked'

/** 복사 결과. 실제 Clipboard 호출은 컨테이너가 하고 결과만 내려 준다. */
export type WaitingCopyResult = 'idle' | 'copied' | 'failed'

/** 진행 중인 일행 조작. 같은 조작의 중복 제출을 막는 데 쓴다. */
export type WaitingPartyPendingAction =
  | { kind: 'issueInvitation' }
  | { kind: 'revokeInvitation' }
  | { kind: 'depart' }
  | { kind: 'removeMember'; membershipId: string }
  | { kind: 'proposeTransfer' }
  | { kind: 'acceptTransfer' }
  | { kind: 'rejectTransfer' }
  | { kind: 'revokeTransfer' }

export type WaitingPartyActionKind = WaitingPartyPendingAction['kind']

/**
 * 일행 조작 실패 사유.
 *
 * 이름은 서버 오류 code가 아니라 화면이 구분해야 하는 복구 경로다. 어떤 code를
 * 어느 이름으로 옮길지는 API 연결 계층이 정한다. 화면이 오류 message 문자열을
 * 파싱해 분기하지 않기 위한 경계다.
 */
export type WaitingPartyErrorCode =
  | 'VERSION_CONFLICT'
  | 'MUTATION_NOT_ALLOWED'
  | 'CAPACITY_EXCEEDED'
  | 'INVITATION_INVALID'
  | 'TRANSFER_INVALID'
  | 'NOT_FOUND'
  | 'REQUEST_FAILED'

export interface WaitingPartyErrorView {
  /** 실패한 조작. 오류를 그 조작 근처에 붙이기 위해 받는다. */
  action: WaitingPartyActionKind
  code: WaitingPartyErrorCode
  /** 서버가 준 문구. 있으면 그대로 쓰고, 없으면 아래 표의 기본 문구를 쓴다. */
  message?: string
}

export interface WaitingPartyGuidance {
  title: string
  description: string
  /** 같은 요청을 다시 보내 볼 수 있는지. 상태가 어긋난 실패는 재시도가 답이 아니다. */
  retryable: boolean
}

/**
 * 사유별 안내와 재시도 가능 여부.
 *
 * 버전 충돌·상태 불허·인원 초과는 최신 상태를 다시 받아야 풀리므로 같은 요청을
 * 다시 보내는 버튼을 주지 않는다. 눌러도 같은 실패가 반복된다.
 */
export const WAITING_PARTY_GUIDANCE: Record<
  WaitingPartyErrorCode,
  WaitingPartyGuidance
> = {
  VERSION_CONFLICT: {
    title: '일행 구성이 방금 바뀌었습니다.',
    description: '최신 상태를 다시 확인한 뒤 이어서 진행해 주세요.',
    retryable: false,
  },
  MUTATION_NOT_ALLOWED: {
    title: '지금은 일행 구성을 바꿀 수 없습니다.',
    description:
      '호출이 시작된 뒤에는 일행을 초대하거나 내보낼 수 없습니다. 매장에 직접 문의해 주세요.',
    retryable: false,
  },
  CAPACITY_EXCEEDED: {
    title: '등록한 방문 인원을 넘습니다.',
    description: '합류할 자리가 남지 않았습니다.',
    retryable: false,
  },
  INVITATION_INVALID: {
    title: '사용할 수 없는 초대입니다.',
    description: '기존 초대를 철회하고 새 초대를 발급해 주세요.',
    retryable: false,
  },
  TRANSFER_INVALID: {
    title: '사용할 수 없는 대표자 이전 제안입니다.',
    description:
      '제안이 만료되었거나 이미 처리되었습니다. 최신 상태를 확인해 주세요.',
    retryable: false,
  },
  NOT_FOUND: {
    title: '대상을 찾을 수 없습니다.',
    description: '이미 처리되었을 수 있습니다. 최신 상태를 다시 확인해 주세요.',
    retryable: false,
  },
  REQUEST_FAILED: {
    title: '요청을 처리하지 못했습니다.',
    description: '잠시 후 다시 시도해 주세요.',
    retryable: true,
  },
}

/** 합류 수락 실패 사유. */
export type WaitingJoinErrorCode =
  | 'INVALID_CODE'
  | 'EXPIRED_CODE'
  | 'REVOKED_OR_USED_CODE'
  | 'PARTY_CAPACITY_EXCEEDED'
  | 'ACCOUNT_ACTIVE_WAITING_EXISTS'
  | 'TEAM_STATE_CHANGED'
  | 'REQUEST_FAILED'

export interface WaitingJoinErrorView {
  code: WaitingJoinErrorCode
  message?: string
}

/**
 * 합류 실패 사유별 안내.
 *
 * 서버는 잘못된 코드·만료·철회·사용 완료를 모두 `WAITING_014` 하나로 닫는다.
 * 초대를 열거해 볼 수 없게 하려는 계약이므로, 세 이름을 화면에 두더라도 연결
 * 계층이 구분 근거를 갖지 못하면 `INVALID_CODE`로 옮긴다. 화면이 근거 없이
 * 만료라고 단정하지 않는다.
 */
export const WAITING_JOIN_GUIDANCE: Record<
  WaitingJoinErrorCode,
  WaitingPartyGuidance
> = {
  INVALID_CODE: {
    title: '사용할 수 없는 초대 코드입니다.',
    description:
      '코드를 다시 확인해 주세요. 이미 사용되었거나 만료된 코드일 수도 있습니다.',
    retryable: false,
  },
  EXPIRED_CODE: {
    title: '초대 코드가 만료되었습니다.',
    description:
      '초대 코드는 발급 후 15분 동안만 쓸 수 있습니다. 대표자에게 새 코드를 요청해 주세요.',
    retryable: false,
  },
  REVOKED_OR_USED_CODE: {
    title: '이미 처리된 초대 코드입니다.',
    description:
      '대표자가 초대를 철회했거나 다른 사람이 사용했습니다. 새 코드를 요청해 주세요.',
    retryable: false,
  },
  PARTY_CAPACITY_EXCEEDED: {
    title: '일행 인원이 가득 찼습니다.',
    description: '대표자가 등록한 방문 인원을 넘어 합류할 수 없습니다.',
    retryable: false,
  },
  ACCOUNT_ACTIVE_WAITING_EXISTS: {
    title: '이미 진행 중인 웨이팅이 있습니다.',
    description: '기존 웨이팅을 취소하거나 종료한 뒤 다시 합류해 주세요.',
    retryable: false,
  },
  TEAM_STATE_CHANGED: {
    title: '지금은 이 일행에 합류할 수 없습니다.',
    description:
      '호출이 시작되었거나 웨이팅이 종료되었습니다. 대표자에게 현재 상태를 확인해 주세요.',
    retryable: false,
  },
  REQUEST_FAILED: {
    title: '합류를 처리하지 못했습니다.',
    description: '잠시 후 다시 시도해 주세요.',
    retryable: true,
  },
}

/**
 * 일행 구성을 바꿀 수 있는 상태인지 판정한다.
 *
 * 계약은 `WAITING`에서만 일행 구성 명령을 받고 그 밖의 상태는 모두
 * `WAITING_015`로 거부한다. 호출 이후(`CALLED` 이상)와 종결 상태를 화면이 따로
 * 열거하지 않는다. 상태 enum이 늘어날 때 화면이 조용히 허용하는 쪽으로
 * 기울지 않도록 허용 목록으로 판정한다.
 */
export function canMutateParty(status: WaitingTeamStatus): boolean {
  return status === 'WAITING'
}

/**
 * 합류 계정 수가 등록 방문 인원에 도달했는지 판정한다.
 *
 * `partySize`는 실제 방문 인원수이고 계약은 활성 membership 수가 이를 넘지
 * 않게 한다. 화면이 별도 상한을 만들지 않는다.
 */
export function isPartyFull(
  partySize: number,
  memberships: readonly WaitingPartyMember[],
): boolean {
  return memberships.length >= partySize
}

/** 현재 로그인 사용자의 membership. 없으면 `null`. */
export function findSelfMembership(
  memberships: readonly WaitingPartyMember[],
): WaitingPartyMember | null {
  return memberships.find((member) => member.self) ?? null
}

/**
 * 대표자가 이전 대상으로 고를 수 있는 구성원.
 *
 * 본인은 제외한다. 계약의 이전 대상은 대상 활성 구성원이고, 자기 자신에게
 * 넘기는 제안은 뜻이 없다.
 */
export function transferCandidates(
  memberships: readonly WaitingPartyMember[],
): WaitingPartyMember[] {
  return memberships.filter((member) => !member.self)
}

export interface WaitingInvitationCodeView {
  /** 코드 문자열을 화면에 보여 줄 수 있는지. */
  showsCode: boolean
  /** 보여 줄 코드. `showsCode`가 false면 `null`. */
  code: string | null
  /** 코드를 보여 줄 수 없을 때의 안내. 보여 줄 수 있으면 `null`. */
  notice: string | null
}

/** replay로 원문 코드를 잃었을 때의 복구 안내. 계약이 정한 경로는 철회 후 재발급이다. */
const INVITATION_REPLAY_NOTICE =
  '초대 코드는 처음 발급할 때만 볼 수 있습니다. 이 초대를 철회하고 새로 발급해 주세요.'

/**
 * 발급 상태와 코드 값을 하나의 표시 결정으로 합친다.
 *
 * `issued`인데 코드가 없으면 replay 안내로 강등한다. 두 값이 따로 내려오는
 * 경계에서는 어긋난 조합이 실제로 생기고, 그때 빈 코드 칸을 복사하라고 하면
 * 사용자는 자기가 뭘 잘못했다고 생각한다.
 */
export function resolveInvitationCode(
  view: WaitingInvitationView,
  code: string | null | undefined,
): WaitingInvitationCodeView {
  if (view === 'issued') {
    if (code === null || code === undefined || code.length === 0) {
      return { showsCode: false, code: null, notice: INVITATION_REPLAY_NOTICE }
    }
    return { showsCode: true, code, notice: null }
  }

  if (view === 'issuedWithoutCode') {
    return { showsCode: false, code: null, notice: INVITATION_REPLAY_NOTICE }
  }

  return { showsCode: false, code: null, notice: null }
}

/** 초대가 아직 살아 있어 복사·공유·철회 대상이 되는 상태인지. */
export function isInvitationActive(view: WaitingInvitationView): boolean {
  return view === 'issued' || view === 'issuedWithoutCode'
}

/**
 * 서버 시각을 사람이 읽는 절대 시각으로 옮긴다. 만료 시각과 합류 시각에 쓴다.
 *
 * 남은 시간을 화면이 계산하지 않는다. 클라이언트 시계는 서버와 어긋날 수 있고,
 * 초 단위로 줄어드는 숫자는 보조기술이 계속 다시 읽는다. 만료 여부 자체는
 * 서버 응답을 옮겨 담은 상태값이 정한다.
 */
export function formatDateTime(isoTimestamp: string): string | null {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) {
    return null
  }
  return parsed.toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * 초대 코드 입력을 검증한다. 통과하면 `null`.
 *
 * 코드 형식은 계약이 공개하지 않으므로 길이나 문자 집합을 화면이 단정하지
 * 않는다. 빈 입력만 막아서 서버가 확실히 거절할 요청을 줄인다.
 */
export function validateInvitationCode(value: string): string | null {
  if (value.trim().length === 0) {
    return '초대 코드를 입력해 주세요.'
  }
  return null
}

/** 역할 표시 문구. */
export const WAITING_PARTY_ROLE_LABEL: Record<WaitingPartyRole, string> = {
  REPRESENTATIVE: '대표자',
  MEMBER: '구성원',
}

/**
 * 목록에서 구성원을 부르는 이름.
 *
 * 계약의 `WaitingPartyMember`에는 이름·닉네임·연락처가 없다. 화면이 이름을
 * 지어내거나 `membershipId`를 노출하지 않고 목록 순서로만 가리킨다.
 *
 * 역할은 이름에 넣지 않는다. 같은 줄의 역할 뱃지와 겹쳐 "대표자 대표자"처럼
 * 두 번 읽히고, 이름으로 요소를 찾는 코드도 무엇을 집었는지 흐려진다.
 */
export function memberDisplayName(
  member: WaitingPartyMember,
  index: number,
): string {
  const label = `일행 ${index + 1}`
  return member.self ? `${label} (나)` : label
}
