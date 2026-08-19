import { Alert } from '../../../../shared/ui/Feedback'
import {
  WAITING_PARTY_ROLE_LABEL,
  canMutateParty,
  isPartyFull,
  type WaitingCopyResult,
  type WaitingInvitationView,
  type WaitingPartyErrorView,
  type WaitingPartyMember,
  type WaitingPartyPendingAction,
  type WaitingPartyRole,
  type WaitingTeamStatus,
  type WaitingTransferOffer,
} from '../model/partyViewState'
import { WaitingPartyInvitation } from './WaitingPartyInvitation'
import { WaitingPartyMembers } from './WaitingPartyMembers'
import { WaitingPartyTransfer } from './WaitingPartyTransfer'
import './waitingParty.css'

export interface WaitingPartyPanelProps {
  /** 현재 웨이팅 snapshot의 상태. 구성 변경 허용 여부를 이 값으로 판정한다. */
  teamStatus: WaitingTeamStatus
  /** 현재 snapshot의 팀 version. 화면에 표시하지 않고 선택 초기화 기준으로만 쓴다. */
  teamVersion: number
  /** 등록한 실제 방문 인원수. 합류 계정 수와 다른 값이다. */
  partySize: number
  memberships: readonly WaitingPartyMember[]
  currentUserRole: WaitingPartyRole
  invitation: WaitingInvitationView
  /** 최초 발급 응답의 원문 코드. replay나 재조회에서는 없다. */
  invitationCode?: string | null
  invitationExpiresAt?: string | null
  /** 복사 결과. Clipboard 호출은 컨테이너가 하고 결과만 내려 준다. */
  copyResult?: WaitingCopyResult
  /** 외부 공유 지원 여부. 컨테이너가 판정해 내려 준다. */
  shareSupported?: boolean
  transferOffer?: WaitingTransferOffer | null
  /** 진행 중인 조작. 같은 조작의 중복 제출을 막는다. */
  pendingAction?: WaitingPartyPendingAction | null
  errorView?: WaitingPartyErrorView | null
  onIssueInvitation: () => void
  onCopyInvitation: (code: string) => void
  onShareInvitation: (code: string) => void
  onRevokeInvitation: () => void
  onDepart: () => void
  onRemoveMember: (membershipId: string) => void
  onProposeTransfer: (targetMembershipId: string) => void
  onAcceptTransfer: () => void
  onRejectTransfer: () => void
  onRevokeTransfer: () => void
  onRetry: () => void
}

/**
 * 현재 웨이팅의 일행 목록과 대표자·구성원 action.
 *
 * 자기 조회·라우팅·컨테이너를 갖지 않는 하나의 `section`이다. 현재 웨이팅 상세
 * 화면(#410)이 snapshot을 조회해 props로 내려 주고 이 패널을 그 안에 끼운다.
 * 그래야 상세 화면이 순번·상태·취소를, 이 패널이 일행 구성만 소유하는 경계가
 * 유지된다.
 *
 * API 호출, 멱등 키, `expectedVersion`, 서버 재조회는 모두 컨테이너 몫이다.
 * 이 패널은 어떤 조작이 가능한지와 지금 어떤 상태인지만 표현한다.
 */
export function WaitingPartyPanel({
  teamStatus,
  teamVersion,
  partySize,
  memberships,
  currentUserRole,
  invitation,
  invitationCode,
  invitationExpiresAt,
  copyResult = 'idle',
  shareSupported = false,
  transferOffer = null,
  pendingAction = null,
  errorView = null,
  onIssueInvitation,
  onCopyInvitation,
  onShareInvitation,
  onRevokeInvitation,
  onDepart,
  onRemoveMember,
  onProposeTransfer,
  onAcceptTransfer,
  onRejectTransfer,
  onRevokeTransfer,
  onRetry,
}: WaitingPartyPanelProps) {
  const mutable = canMutateParty(teamStatus)
  const isRepresentative = currentUserRole === 'REPRESENTATIVE'
  const partyFull = isPartyFull(partySize, memberships)

  /*
   * 대표자 이전은 대표자와 제안 대상에게만 뜻이 있다. 제안이 없는 일반 구성원에게
   * 제목만 남은 빈 영역을 보여 주지 않는다.
   */
  const showsTransfer = isRepresentative || transferOffer !== null

  return (
    <section
      className="mi-card waiting-party"
      aria-labelledby="waiting-party-heading"
    >
      <div className="mi-card__body">
        <div className="waiting-party__head">
          <h2 className="waiting-party__title" id="waiting-party-heading">
            일행
          </h2>
          {/*
            방문 인원과 합류 계정 수는 서로 다른 값이다. 등록한 인원수는 실제로
            방문하는 사람 수이고, 합류 계정은 이 웨이팅을 함께 보는 계정 수다.
            한 숫자로 합치면 "4명 중 2명만 왔다"는 뜻으로 읽힌다.
          */}
          <dl className="waiting-party__counts">
            <div className="waiting-party__count">
              <dt>방문 인원</dt>
              <dd>{`${partySize}명`}</dd>
            </div>
            <div className="waiting-party__count">
              <dt>합류한 계정</dt>
              <dd>{`${memberships.length}개`}</dd>
            </div>
          </dl>
          <p className="waiting-party__hint">
            {`내 역할: ${WAITING_PARTY_ROLE_LABEL[currentUserRole]}`}
          </p>
        </div>

        {!mutable && (
          <Alert tone="info" title="지금은 일행 구성을 바꿀 수 없습니다.">
            <p>
              호출이 시작되거나 웨이팅이 끝난 뒤에는 초대·이탈·제거·대표자 이전을
              할 수 없습니다. 현재 일행 목록은 그대로 확인할 수 있습니다.
            </p>
          </Alert>
        )}

        {isRepresentative && mutable && (
          <WaitingPartyInvitation
            view={invitation}
            code={invitationCode}
            expiresAt={invitationExpiresAt}
            copyResult={copyResult}
            shareSupported={shareSupported}
            partyFull={partyFull}
            pendingAction={pendingAction}
            error={errorView}
            onIssueInvitation={onIssueInvitation}
            onCopyInvitation={onCopyInvitation}
            onShareInvitation={onShareInvitation}
            onRevokeInvitation={onRevokeInvitation}
            onRetry={onRetry}
          />
        )}

        <WaitingPartyMembers
          memberships={memberships}
          currentUserRole={currentUserRole}
          mutable={mutable}
          pendingAction={pendingAction}
          error={errorView}
          onRemoveMember={onRemoveMember}
          onDepart={onDepart}
          onRetry={onRetry}
        />

        {showsTransfer && (
          <WaitingPartyTransfer
            memberships={memberships}
            currentUserRole={currentUserRole}
            teamVersion={teamVersion}
            mutable={mutable}
            offer={transferOffer}
            pendingAction={pendingAction}
            error={errorView}
            onProposeTransfer={onProposeTransfer}
            onAcceptTransfer={onAcceptTransfer}
            onRejectTransfer={onRejectTransfer}
            onRevokeTransfer={onRevokeTransfer}
            onRetry={onRetry}
          />
        )}
      </div>
    </section>
  )
}
