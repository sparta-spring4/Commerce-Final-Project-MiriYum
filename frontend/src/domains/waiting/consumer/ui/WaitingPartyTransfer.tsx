import { useState } from 'react'
import { Button } from '../../../../shared/ui/Button'
import { SelectField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import {
  formatDateTime,
  memberDisplayName,
  transferCandidates,
  type WaitingPartyErrorView,
  type WaitingPartyMember,
  type WaitingPartyPendingAction,
  type WaitingPartyRole,
  type WaitingTransferOffer,
} from '../model/partyViewState'
import { WaitingPartyErrorAlert } from './WaitingPartyErrorAlert'

interface Props {
  memberships: readonly WaitingPartyMember[]
  currentUserRole: WaitingPartyRole
  /**
   * 현재 snapshot의 팀 version.
   *
   * 화면에 표시하지 않는다. version이 오르면 일행 구성이 바뀐 것이므로 그 전에
   * 고른 이전 대상 선택을 버리는 기준으로만 쓴다.
   */
  teamVersion: number
  mutable: boolean
  offer: WaitingTransferOffer | null
  pendingAction: WaitingPartyPendingAction | null
  error: WaitingPartyErrorView | null
  onProposeTransfer: (targetMembershipId: string) => void
  onAcceptTransfer: () => void
  onRejectTransfer: () => void
  onRevokeTransfer: () => void
  onRetry: () => void
}

/** 이미 끝난 제안의 상태. 진행 중인 `PROPOSED`는 결과가 아니므로 뺀다. */
type SettledTransferStatus = Exclude<WaitingTransferOffer['status'], 'PROPOSED'>

/** 제안 결과 상태별 안내. */
const SETTLED_NOTICE: Record<
  SettledTransferStatus,
  { title: string; description: string }
> = {
  ACCEPTED: {
    title: '대표자가 바뀌었습니다.',
    description: '이제 새 대표자가 일행 구성과 웨이팅을 관리합니다.',
  },
  REJECTED: {
    title: '대표자 이전을 거절했습니다.',
    description: '대표자는 그대로 유지됩니다.',
  },
  REVOKED: {
    title: '대표자 이전 제안을 철회했습니다.',
    description: '대표자는 그대로 유지됩니다.',
  },
  EXPIRED: {
    title: '대표자 이전 제안이 만료되었습니다.',
    description: '제안은 5분 동안만 유효합니다. 대표자는 그대로 유지됩니다.',
  },
}

/**
 * 대표자 이전 제안·수락·거절·철회.
 *
 * 계약은 대상이 수락하기 전까지 기존 대표자를 그대로 둔다. 그래서 이 화면은
 * 제안 중에도 역할 표시를 바꾸지 않고, 아직 확정되지 않았다는 사실만 덧붙인다.
 * 낙관적으로 새 대표자를 그리면 거절·만료·철회 뒤에 화면이 되돌아가면서
 * 사용자는 자기 권한이 오갔다고 느낀다.
 */
export function WaitingPartyTransfer({
  memberships,
  currentUserRole,
  teamVersion,
  mutable,
  offer,
  pendingAction,
  error,
  onProposeTransfer,
  onAcceptTransfer,
  onRejectTransfer,
  onRevokeTransfer,
  onRetry,
}: Props) {
  /*
   * 고른 대상과 그때의 팀 version을 함께 들고 있는다. version이 오른 뒤에도
   * 선택을 유지하면 이미 빠진 구성원에게 제안을 보내게 된다.
   */
  const [selection, setSelection] = useState({
    teamVersion,
    membershipId: '',
  })

  const isRepresentative = currentUserRole === 'REPRESENTATIVE'
  const candidates = transferCandidates(memberships)
  const self = memberships.find((member) => member.self) ?? null

  const selectedId =
    selection.teamVersion === teamVersion &&
    candidates.some((candidate) => candidate.membershipId === selection.membershipId)
      ? selection.membershipId
      : ''

  const proposing = pendingAction?.kind === 'proposeTransfer'
  const accepting = pendingAction?.kind === 'acceptTransfer'
  const rejecting = pendingAction?.kind === 'rejectTransfer'
  const revoking = pendingAction?.kind === 'revokeTransfer'
  const busy = proposing || accepting || rejecting || revoking

  const transferError =
    error !== null &&
    (error.action === 'proposeTransfer' ||
      error.action === 'acceptTransfer' ||
      error.action === 'rejectTransfer' ||
      error.action === 'revokeTransfer')
      ? error
      : null

  const pendingOffer = offer !== null && offer.status === 'PROPOSED' ? offer : null
  /*
   * 결과 안내에는 상태만 필요하다. 제안 객체째로 들고 가면 `PROPOSED`가 섞인
   * 넓은 타입이 그대로 남아 안내표를 찾을 때 빠진 칸이 생긴다.
   */
  const settledStatus: SettledTransferStatus | null =
    offer !== null && offer.status !== 'PROPOSED' ? offer.status : null

  const targetMember =
    pendingOffer === null
      ? null
      : (memberships.find(
          (member) => member.membershipId === pendingOffer.targetMembershipId,
        ) ?? null)
  const targetIndex =
    pendingOffer === null
      ? -1
      : memberships.findIndex(
          (member) => member.membershipId === pendingOffer.targetMembershipId,
        )
  const isTarget =
    pendingOffer !== null &&
    self !== null &&
    self.membershipId === pendingOffer.targetMembershipId

  const expiresLabel =
    pendingOffer === null ? null : formatDateTime(pendingOffer.expiresAt)

  return (
    <div className="waiting-party__transfer">
      <h3 className="waiting-party__subtitle">대표자 이전</h3>

      {settledStatus !== null && (
        <Alert
          tone={settledStatus === 'ACCEPTED' ? 'info' : 'warning'}
          title={SETTLED_NOTICE[settledStatus].title}
        >
          <p>{SETTLED_NOTICE[settledStatus].description}</p>
        </Alert>
      )}

      {pendingOffer !== null && (
        <Alert tone="warning" title="대표자 이전 제안이 진행 중입니다.">
          <p>
            {targetMember === null
              ? '대상 구성원의 응답을 기다리고 있습니다.'
              : `${memberDisplayName(targetMember, targetIndex)}의 응답을 기다리고 있습니다.`}
          </p>
          {/* 아직 바뀌지 않았다는 사실을 문구로 못 박는다. 역할 표시는 그대로 둔다. */}
          <p>수락하기 전까지 대표자는 그대로 유지됩니다.</p>
          {expiresLabel !== null && <p>{`${expiresLabel}까지 유효합니다.`}</p>}
        </Alert>
      )}

      {transferError !== null && (
        <WaitingPartyErrorAlert error={transferError} onRetry={onRetry} />
      )}

      {/*
        대상 본인의 응답. 제안을 받은 사람에게만 준다.
        수락·거절·철회도 계약이 `WAITING`에서만 받으므로 구성 변경이 막힌
        상태에서는 눌러 볼 수 있게 두지 않고 안내만 남긴다.
      */}
      {isTarget && mutable && (
        <div className="waiting-party__actions">
          <Button
            variant="primary"
            loading={accepting}
            disabled={busy}
            onClick={onAcceptTransfer}
          >
            대표자 맡기
          </Button>
          <Button
            variant="ghost"
            loading={rejecting}
            disabled={busy}
            onClick={onRejectTransfer}
          >
            거절
          </Button>
        </div>
      )}

      {/* 제안자의 철회. */}
      {isRepresentative && pendingOffer !== null && mutable && (
        <div className="waiting-party__actions">
          <Button
            variant="ghost"
            loading={revoking}
            disabled={busy}
            onClick={onRevokeTransfer}
          >
            제안 철회
          </Button>
        </div>
      )}

      {/*
        새 제안. 계약은 팀당 하나의 활성 제안만 두므로 진행 중일 때는 대상 선택을
        열지 않는다.
      */}
      {isRepresentative && pendingOffer === null && mutable && (
        <>
          {candidates.length === 0 ? (
            <p className="waiting-party__hint">
              대표자를 넘길 구성원이 없습니다. 먼저 일행을 초대해 주세요.
            </p>
          ) : (
            <div className="waiting-party__transfer-form">
              <SelectField
                label="대표자를 넘길 구성원"
                value={selectedId}
                disabled={busy}
                onChange={(event) =>
                  setSelection({ teamVersion, membershipId: event.target.value })
                }
              >
                <option value="">선택해 주세요</option>
                {candidates.map((candidate) => (
                  <option key={candidate.membershipId} value={candidate.membershipId}>
                    {memberDisplayName(
                      candidate,
                      memberships.indexOf(candidate),
                    )}
                  </option>
                ))}
              </SelectField>
              <Button
                variant="secondary"
                loading={proposing}
                disabled={busy || selectedId === ''}
                onClick={() => onProposeTransfer(selectedId)}
              >
                대표자 이전 제안
              </Button>
              <p className="waiting-party__hint">
                제안은 5분 동안만 유효하고, 대상이 수락할 때까지 대표자는 그대로입니다.
              </p>
            </div>
          )}
        </>
      )}
    </div>
  )
}
