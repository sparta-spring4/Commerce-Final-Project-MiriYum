import { useState } from 'react'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import {
  WAITING_PARTY_ROLE_LABEL,
  formatDateTime,
  memberDisplayName,
  type WaitingPartyErrorView,
  type WaitingPartyMember,
  type WaitingPartyPendingAction,
  type WaitingPartyRole,
} from '../model/partyViewState'
import { WaitingPartyConfirmDialog } from './WaitingPartyConfirmDialog'
import { WaitingPartyErrorAlert } from './WaitingPartyErrorAlert'

interface Props {
  memberships: readonly WaitingPartyMember[]
  currentUserRole: WaitingPartyRole
  /** 일행 구성을 바꿀 수 있는 상태인지. 호출 이후·종결에서는 false다. */
  mutable: boolean
  pendingAction: WaitingPartyPendingAction | null
  error: WaitingPartyErrorView | null
  onRemoveMember: (membershipId: string) => void
  onDepart: () => void
  onRetry: () => void
}

/**
 * 일행 구성원 목록과 허용된 이탈·제거.
 *
 * 계약이 정한 권한을 그대로 화면 경계로 옮긴다. 대표자는 다른 구성원만 제거하고,
 * 구성원은 본인만 이탈한다. 대표자에게는 본인 이탈 버튼을 주지 않는다. 팀에는
 * 대표자의 활성 membership이 항상 있어야 하므로, 대표자가 빠지려면 이탈이 아니라
 * 대표자 이전을 먼저 해야 한다.
 *
 * 계약의 `WaitingPartyMember`에는 이름이 없다. 목록 순서와 역할로만 가리키고
 * `membershipId`를 화면에 노출하지 않는다.
 */
export function WaitingPartyMembers({
  memberships,
  currentUserRole,
  mutable,
  pendingAction,
  error,
  onRemoveMember,
  onDepart,
  onRetry,
}: Props) {
  const [removeTargetId, setRemoveTargetId] = useState<string | null>(null)
  const [departRequested, setDepartRequested] = useState(false)

  const isRepresentative = currentUserRole === 'REPRESENTATIVE'
  const departing = pendingAction?.kind === 'depart'
  const removingId =
    pendingAction?.kind === 'removeMember' ? pendingAction.membershipId : null

  /*
   * 제거가 끝나면 대상이 목록에서 사라지므로 다이얼로그도 함께 닫힌다.
   * 성공 통보를 따로 받지 않아도 되도록 목록에서 대상을 다시 찾아 파생시킨다.
   */
  const removeTarget =
    memberships.find((member) => member.membershipId === removeTargetId) ?? null
  const removeTargetIndex = memberships.findIndex(
    (member) => member.membershipId === removeTargetId,
  )

  const removeError = error?.action === 'removeMember' ? error : null
  const departError = error?.action === 'depart' ? error : null

  return (
    <div className="waiting-party__members">
      <h3 className="waiting-party__subtitle">일행 구성</h3>

      <ul className="waiting-party__list">
        {memberships.map((member, index) => {
          const name = memberDisplayName(member, index)
          const removable = isRepresentative && !member.self && mutable
          const joinedLabel = formatDateTime(member.joinedAt)

          return (
            <li className="waiting-party__row" key={member.membershipId}>
              <div className="waiting-party__row-main">
                <p className="waiting-party__row-name">
                  {name}
                  {member.self && <Badge tone="positive">나</Badge>}
                </p>
                <p className="waiting-party__row-meta">
                  <Badge
                    tone={
                      member.role === 'REPRESENTATIVE' ? 'attention' : 'neutral'
                    }
                  >
                    {WAITING_PARTY_ROLE_LABEL[member.role]}
                  </Badge>
                  {joinedLabel !== null && (
                    <span className="waiting-party__row-joined">{`${joinedLabel} 합류`}</span>
                  )}
                </p>
              </div>

              {removable && (
                <Button
                  variant="ghost"
                  size="sm"
                  loading={removingId === member.membershipId}
                  disabled={removingId !== null || departing}
                  onClick={() => setRemoveTargetId(member.membershipId)}
                >
                  {`${name} 내보내기`}
                </Button>
              )}
            </li>
          )
        })}
      </ul>

      {/*
        구성원 본인 이탈. 대표자에게는 이 버튼이 없다. 계약이 대표자의 이탈을
        허용하지 않으므로 눌러 볼 수 있게 두면 실패만 돌려받는다.
      */}
      {!isRepresentative && mutable && (
        <div className="waiting-party__actions">
          <Button
            variant="ghost"
            loading={departing}
            disabled={departing || removingId !== null}
            onClick={() => setDepartRequested(true)}
          >
            웨이팅 일행에서 나가기
          </Button>
        </div>
      )}

      {/*
        다이얼로그가 닫힌 뒤에도 실패 사실이 남도록 목록 아래에 한 번 더 둔다.
        확인 창을 닫으면 실패가 조용히 사라지면 사용자는 처리가 됐다고 읽는다.
      */}
      {removeError !== null && removeTarget === null && (
        <WaitingPartyErrorAlert error={removeError} onRetry={onRetry} />
      )}

      {departError !== null && !departRequested && (
        <WaitingPartyErrorAlert error={departError} onRetry={onRetry} />
      )}

      {removeTarget !== null && (
        <WaitingPartyConfirmDialog
          /*
           * 제목에 조사를 붙이지 않는다. 이름이 숫자로 끝나서 "일행 2을"처럼
           * 어긋난 조사가 나온다. 무엇을 하는 자리인지는 동작 이름으로 말한다.
           */
          title={`${memberDisplayName(removeTarget, removeTargetIndex)} 내보내기`}
          description={
            <>
              <p>내보내면 이 사람은 다시 초대를 받아야 합류할 수 있습니다.</p>
              {removeError !== null && (
                <WaitingPartyErrorAlert error={removeError} onRetry={onRetry} />
              )}
            </>
          }
          confirmLabel="내보내기"
          submitting={removingId === removeTarget.membershipId}
          onConfirm={() => onRemoveMember(removeTarget.membershipId)}
          onCancel={() => setRemoveTargetId(null)}
        />
      )}

      {departRequested && (
        <WaitingPartyConfirmDialog
          title="일행에서 나갈까요?"
          description={
            <>
              <p>나가면 이 웨이팅의 순번을 함께 쓸 수 없습니다.</p>
              {departError !== null && (
                <WaitingPartyErrorAlert error={departError} onRetry={onRetry} />
              )}
            </>
          }
          confirmLabel="나가기"
          submitting={departing}
          onConfirm={onDepart}
          onCancel={() => setDepartRequested(false)}
        />
      )}
    </div>
  )
}
