import { useState } from 'react'
import { Button } from '../../../../shared/ui/Button'
import { Alert } from '../../../../shared/ui/Feedback'
import {
  formatDateTime,
  isInvitationActive,
  resolveInvitationCode,
  type WaitingCopyResult,
  type WaitingInvitationView,
  type WaitingPartyErrorView,
  type WaitingPartyPendingAction,
} from '../model/partyViewState'
import { WaitingPartyConfirmDialog } from './WaitingPartyConfirmDialog'
import { WaitingPartyErrorAlert } from './WaitingPartyErrorAlert'

interface Props {
  view: WaitingInvitationView
  code?: string | null
  expiresAt?: string | null
  copyResult: WaitingCopyResult
  /** 이 브라우저가 외부 공유를 지원하는지. 컨테이너가 판정해 내려 준다. */
  shareSupported: boolean
  /** 등록한 방문 인원이 이미 찼는지. 자리가 없으면 초대를 발급하지 않는다. */
  partyFull: boolean
  pendingAction: WaitingPartyPendingAction | null
  error: WaitingPartyErrorView | null
  onIssueInvitation: () => void
  onCopyInvitation: (code: string) => void
  onShareInvitation: (code: string) => void
  onRevokeInvitation: () => void
  onRetry: () => void
}

/**
 * 대표자의 일행 초대 발급·복사·공유·철회.
 *
 * 이 컴포넌트는 Clipboard API와 Web Share API를 직접 부르지 않는다. 두 API는
 * 브라우저마다 지원과 권한이 다르고 사용자 제스처 안에서만 동작하므로, 호출과
 * 결과 판정은 컨테이너가 소유하고 화면은 결과만 표현한다.
 *
 * 초대 코드는 화면 상태로만 지나간다. storage·URL·query에 남기지 않는다.
 */
export function WaitingPartyInvitation({
  view,
  code,
  expiresAt,
  copyResult,
  shareSupported,
  partyFull,
  pendingAction,
  error,
  onIssueInvitation,
  onCopyInvitation,
  onShareInvitation,
  onRevokeInvitation,
  onRetry,
}: Props) {
  const [revokeRequested, setRevokeRequested] = useState(false)

  const resolved = resolveInvitationCode(view, code)
  /* 코드 표시 여부와 값을 한 변수로 좁혀 둔다. 두 조건을 따로 들고 다니면 자리마다 어긋난다. */
  const shownCode = resolved.showsCode ? resolved.code : null
  const active = isInvitationActive(view)
  const issuing = view === 'issuing' || pendingAction?.kind === 'issueInvitation'
  const revoking = pendingAction?.kind === 'revokeInvitation'
  const busy = issuing || revoking

  /*
   * 철회가 끝나 초대가 사라지면 요청 표시도 함께 내린다.
   *
   * 열림 여부를 `revokeRequested && active`로만 파생시키면 표시가 그대로 남아
   * 있어서, 대표자가 새 초대를 발급하는 순간 아무도 누르지 않은 확인 창이 저절로
   * 열린다. props가 바뀐 렌더에서 상태를 맞춰 두면 그 일이 생기지 않는다.
   */
  if (revokeRequested && !active) {
    setRevokeRequested(false)
  }

  const expiresLabel =
    expiresAt === null || expiresAt === undefined
      ? null
      : formatDateTime(expiresAt)

  const issueError = error?.action === 'issueInvitation' ? error : null
  const revokeError = error?.action === 'revokeInvitation' ? error : null

  return (
    <div className="waiting-party__invitation">
      <div className="waiting-party__invitation-head">
        <h3 className="waiting-party__subtitle">일행 초대</h3>
        <p className="waiting-party__hint">
          초대 코드를 직접 전달해 주세요. 문자나 메시지를 자동으로 보내지 않습니다.
        </p>
      </div>

      {view === 'none' && (
        <p className="waiting-party__hint">아직 발급한 초대가 없습니다.</p>
      )}

      {view === 'expired' && (
        <Alert tone="info" title="초대가 만료되었습니다.">
          <p>초대 코드는 발급 후 15분 동안만 쓸 수 있습니다. 새로 발급해 주세요.</p>
        </Alert>
      )}

      {view === 'revoked' && (
        <Alert tone="info" title="초대를 철회했습니다.">
          <p>이 코드는 더 이상 쓸 수 없습니다. 필요하면 새로 발급해 주세요.</p>
        </Alert>
      )}

      {shownCode !== null && (
        <div className="waiting-party__code-box">
          {/*
            코드는 읽어서 옮겨 적는 값이다. 화면에서 고를 수 있어야 하므로 버튼
            안에 넣지 않고, 무엇인지 label로 먼저 말한다.
          */}
          <p className="waiting-party__code-label" id="waiting-party-code-label">
            초대 코드
          </p>
          <p
            className="waiting-party__code"
            aria-labelledby="waiting-party-code-label"
          >
            {shownCode}
          </p>
        </div>
      )}

      {resolved.notice !== null && (
        <Alert tone="warning" title="초대 코드를 다시 볼 수 없습니다.">
          <p>{resolved.notice}</p>
        </Alert>
      )}

      {active && expiresLabel !== null && (
        <p className="waiting-party__hint">{`${expiresLabel}까지 사용할 수 있습니다.`}</p>
      )}

      {/*
        복사·공유 결과는 버튼 옆 색 변화로만 알 수 없다. 같은 자리에서 문구가
        바뀌도록 live 영역을 항상 두고 내용만 갈아 끼운다.
      */}
      <p className="waiting-party__live" role="status" aria-live="polite">
        {copyResult === 'copied' && '초대 코드를 복사했습니다.'}
        {copyResult === 'failed' &&
          '복사하지 못했습니다. 코드를 직접 선택해 복사해 주세요.'}
      </p>

      {issueError !== null && (
        <WaitingPartyErrorAlert error={issueError} onRetry={onRetry} />
      )}

      {/*
        자리가 찼으면 새로 발급하지 않는다. 발급 자체는 서버가 받지만 그 코드로는
        아무도 합류할 수 없어서, 코드를 전달한 대표자만 헛걸음한다.
      */}
      {partyFull && (
        <Alert tone="info" title="일행 자리가 모두 찼습니다.">
          <p>등록한 방문 인원만큼 합류했습니다. 더 초대할 자리가 없습니다.</p>
        </Alert>
      )}

      <div className="waiting-party__actions">
        <Button
          variant={active ? 'secondary' : 'primary'}
          loading={issuing}
          disabled={busy || partyFull}
          onClick={onIssueInvitation}
        >
          {active ? '새 초대 코드 발급' : '일행 초대'}
        </Button>

        {shownCode !== null && (
          <>
            <Button
              variant="secondary"
              disabled={busy}
              onClick={() => onCopyInvitation(shownCode)}
            >
              코드 복사
            </Button>
            {shareSupported && (
              <Button
                variant="ghost"
                disabled={busy}
                onClick={() => onShareInvitation(shownCode)}
              >
                공유하기
              </Button>
            )}
          </>
        )}

        {active && (
          <Button
            variant="ghost"
            disabled={busy}
            onClick={() => setRevokeRequested(true)}
          >
            초대 철회
          </Button>
        )}
      </div>

      {shownCode !== null && !shareSupported && (
        <p className="waiting-party__hint">
          이 브라우저는 공유를 지원하지 않습니다. 코드를 복사해 전달해 주세요.
        </p>
      )}

      {revokeRequested && (
        <WaitingPartyConfirmDialog
          title="초대를 철회할까요?"
          description={
            <>
              <p>철회하면 이 코드로는 합류할 수 없습니다.</p>
              {revokeError !== null && (
                <WaitingPartyErrorAlert error={revokeError} onRetry={onRetry} />
              )}
            </>
          }
          confirmLabel="초대 철회"
          submitting={revoking}
          onConfirm={onRevokeInvitation}
          onCancel={() => setRevokeRequested(false)}
        />
      )}
    </div>
  )
}
