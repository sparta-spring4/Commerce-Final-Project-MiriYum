import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import { isApiError } from '../../../../shared/api/apiError'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import {
  consumerWaitingKeys,
  departWaitingPartyMembership,
  issueWaitingPartyInvitation,
  proposeWaitingRepresentativeTransfer,
  removeWaitingPartyMembership,
  revokeWaitingPartyInvitation,
  revokeWaitingRepresentativeTransfer,
  type ConsumerWaitingSnapshot,
} from '../api/queries'
import { toWaitingPartyError } from '../model/errors'
import type {
  WaitingCopyResult,
  WaitingInvitationView,
  WaitingPartyErrorView,
  WaitingPartyPendingAction,
  WaitingTransferOffer,
} from '../model/partyViewState'
import { WaitingPartyPanel } from './WaitingPartyPanel'

interface Props {
  /** #410 현재 웨이팅 화면이 소유하는 중앙 snapshot. */
  snapshot: ConsumerWaitingSnapshot
}

interface InvitationState {
  view: WaitingInvitationView
  invitationId: string | null
  code: string | null
  expiresAt: string | null
}

interface CommandAttempt {
  action: WaitingPartyPendingAction
  signature: string
  idempotencyKey: string
  run: (idempotencyKey: string, isCurrent: () => boolean) => Promise<void>
}

const EMPTY_INVITATION: InvitationState = {
  view: 'none',
  invitationId: null,
  code: null,
  expiresAt: null,
}

/**
 * 준비된 panel props를 실제 Waiting API에 연결한다.
 *
 * 초대 원문과 제안 ID는 이 컴포넌트의 메모리 상태에만 둔다. 따라서 새로고침이나
 * unmount 뒤에는 서버에 없는 조회 계약을 가장해 복원하지 않는다.
 */
export function WaitingPartyPanelContainer({ snapshot }: Props) {
  return (
    <WaitingPartyPanelStateful
      key={snapshot.waitingTeamId}
      snapshot={snapshot}
    />
  )
}

function WaitingPartyPanelStateful({ snapshot }: Props) {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()
  const [invitation, setInvitation] = useState<InvitationState>(EMPTY_INVITATION)
  const [transferOffer, setTransferOffer] = useState<WaitingTransferOffer | null>(
    null,
  )
  const [copyResult, setCopyResult] = useState<WaitingCopyResult>('idle')
  const [pendingAction, setPendingAction] =
    useState<WaitingPartyPendingAction | null>(null)
  const [errorView, setErrorView] = useState<WaitingPartyErrorView | null>(null)
  const attemptRef = useRef<CommandAttempt | null>(null)
  const runningRef = useRef<CommandAttempt | null>(null)
  const activeRef = useRef(true)

  /*
   * mount마다 다시 켠다. cleanup만 두면 StrictMode의 mount→unmount→mount에서
   * 두 번째 mount가 꺼진 ref를 물려받아 모든 명령 결과가 조용히 버려진다.
   * 단위 테스트는 StrictMode로 렌더하지 않아 이 차이를 잡지 못한다.
   */
  useEffect(() => {
    activeRef.current = true
    return () => {
      activeRef.current = false
    }
  }, [])

  useEffect(() => {
    // version이 바뀌면 이전 expectedVersion과 멱등 키를 새 명령에 재사용하지 않는다.
    attemptRef.current = null
    setErrorView(null)
  }, [snapshot.version])

  function invalidateCurrentWaiting() {
    void queryClient.invalidateQueries({ queryKey: consumerWaitingKeys.current })
  }

  function publishSnapshot(next: ConsumerWaitingSnapshot | null) {
    queryClient.setQueryData(consumerWaitingKeys.current, next)
    invalidateCurrentWaiting()
  }

  async function execute(attempt: CommandAttempt) {
    // 모든 명령이 같은 team version을 사용하므로 한 번에 하나만 전송한다.
    if (runningRef.current !== null) return
    runningRef.current = attempt
    setPendingAction(attempt.action)
    setErrorView(null)
    const isCurrent = () =>
      activeRef.current && runningRef.current === attempt
    try {
      await attempt.run(attempt.idempotencyKey, isCurrent)
      if (isCurrent() && attemptRef.current === attempt) attemptRef.current = null
    } catch (error) {
      if (!isCurrent()) return
      setErrorView(toWaitingPartyError(attempt.action.kind, error))
      if (
        isApiError(error) &&
        (error.code === 'WAITING_003' ||
          error.code === 'WAITING_005' ||
          error.code === 'WAITING_015')
      ) {
        invalidateCurrentWaiting()
      }
    } finally {
      if (runningRef.current === attempt) {
        runningRef.current = null
        if (activeRef.current) setPendingAction(null)
      }
    }
  }

  function startCommand(
    action: WaitingPartyPendingAction,
    signature: string,
    run: CommandAttempt['run'],
  ) {
    // 다른 section의 활성 버튼이 눌려도 현재 명령의 재시도 정보를 덮지 않는다.
    if (runningRef.current !== null) return
    const previous = attemptRef.current
    const attempt =
      previous !== null &&
      previous.action.kind === action.kind &&
      previous.signature === signature
        ? previous
        : { action, signature, idempotencyKey: createIdempotencyKey(), run }
    attemptRef.current = attempt
    void execute(attempt)
  }

  function issueInvitation() {
    startCommand(
      { kind: 'issueInvitation' },
      `issue:${snapshot.waitingTeamId}:${snapshot.version}`,
      async (idempotencyKey, isCurrent) => {
        const result = await issueWaitingPartyInvitation(apiClient, {
          teamId: snapshot.waitingTeamId,
          expectedVersion: snapshot.version,
          idempotencyKey,
        })
        if (!isCurrent()) return
        setInvitation({
          view: result.invitationCode === null ? 'issuedWithoutCode' : 'issued',
          invitationId: result.invitationId,
          code: result.invitationCode,
          expiresAt: result.expiresAt,
        })
        setCopyResult('idle')
        invalidateCurrentWaiting()
      },
    )
  }

  function revokeInvitation() {
    if (invitation.invitationId === null) return
    const invitationId = invitation.invitationId
    startCommand(
      { kind: 'revokeInvitation' },
      `revoke-invitation:${snapshot.waitingTeamId}:${invitationId}:${snapshot.version}`,
      async (idempotencyKey, isCurrent) => {
        await revokeWaitingPartyInvitation(apiClient, {
          teamId: snapshot.waitingTeamId,
          invitationId,
          expectedVersion: snapshot.version,
          idempotencyKey,
        })
        if (!isCurrent()) return
        setInvitation({ ...EMPTY_INVITATION, view: 'revoked' })
        setCopyResult('idle')
        invalidateCurrentWaiting()
      },
    )
  }

  function depart() {
    startCommand(
      { kind: 'depart' },
      `depart:${snapshot.waitingTeamId}:${snapshot.version}`,
      async (idempotencyKey, isCurrent) => {
        await departWaitingPartyMembership(apiClient, {
          teamId: snapshot.waitingTeamId,
          expectedVersion: snapshot.version,
          idempotencyKey,
        })
        if (!isCurrent()) return
        publishSnapshot(null)
      },
    )
  }

  function removeMember(membershipId: string) {
    startCommand(
      { kind: 'removeMember', membershipId },
      `remove:${snapshot.waitingTeamId}:${membershipId}:${snapshot.version}`,
      async (idempotencyKey, isCurrent) => {
        const next = await removeWaitingPartyMembership(apiClient, {
          teamId: snapshot.waitingTeamId,
          membershipId,
          expectedVersion: snapshot.version,
          idempotencyKey,
        })
        if (!isCurrent()) return
        publishSnapshot(next)
      },
    )
  }

  function proposeTransfer(targetMembershipId: string) {
    startCommand(
      { kind: 'proposeTransfer' },
      `propose:${snapshot.waitingTeamId}:${targetMembershipId}:${snapshot.version}`,
      async (idempotencyKey, isCurrent) => {
        const offer = await proposeWaitingRepresentativeTransfer(apiClient, {
          teamId: snapshot.waitingTeamId,
          targetMembershipId,
          expectedVersion: snapshot.version,
          idempotencyKey,
        })
        if (!isCurrent()) return
        setTransferOffer(offer)
        invalidateCurrentWaiting()
      },
    )
  }

  function revokeTransfer() {
    if (transferOffer === null) return
    const offerId = transferOffer.offerId
    startCommand(
      { kind: 'revokeTransfer' },
      `revoke-transfer:${snapshot.waitingTeamId}:${offerId}:${snapshot.version}`,
      async (idempotencyKey, isCurrent) => {
        const offer = await revokeWaitingRepresentativeTransfer(apiClient, {
          teamId: snapshot.waitingTeamId,
          offerId,
          expectedVersion: snapshot.version,
          idempotencyKey,
        })
        if (!isCurrent()) return
        setTransferOffer(offer)
        invalidateCurrentWaiting()
      },
    )
  }

  async function copyInvitation(code: string) {
    try {
      await navigator.clipboard.writeText(code)
      setCopyResult('copied')
    } catch {
      setCopyResult('failed')
    }
  }

  async function shareInvitation(code: string) {
    try {
      await navigator.share?.({ text: code })
    } catch {
      // 공유 취소·실패를 성공으로 표시하지 않는다.
      setCopyResult('failed')
    }
  }

  function retry() {
    if (attemptRef.current !== null) void execute(attemptRef.current)
  }

  const self = snapshot.memberships.find((membership) => membership.self)
  if (self === undefined) return null

  return (
    <WaitingPartyPanel
      teamStatus={snapshot.status}
      teamVersion={snapshot.version}
      partySize={snapshot.partySize}
      memberships={snapshot.memberships}
      currentUserRole={self.role}
      invitation={invitation.view}
      invitationCode={invitation.code}
      invitationExpiresAt={invitation.expiresAt}
      copyResult={copyResult}
      shareSupported={typeof navigator.share === 'function'}
      transferOffer={transferOffer}
      pendingAction={pendingAction}
      errorView={errorView}
      onIssueInvitation={issueInvitation}
      onCopyInvitation={(code) => void copyInvitation(code)}
      onShareInvitation={(code) => void shareInvitation(code)}
      onRevokeInvitation={revokeInvitation}
      onDepart={depart}
      onRemoveMember={removeMember}
      onProposeTransfer={proposeTransfer}
      /* offer 조회 계약이 없어 대상 사용자의 수락·거절은 연결하지 않는다. */
      onAcceptTransfer={() => undefined}
      onRejectTransfer={() => undefined}
      onRevokeTransfer={revokeTransfer}
      onRetry={retry}
    />
  )
}
