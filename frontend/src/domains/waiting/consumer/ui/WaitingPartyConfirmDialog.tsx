import type { ReactNode } from 'react'
import { Button } from '../../../../shared/ui/Button'
import { useDialogFocus } from '../../../../shared/ui/useDialogFocus'

/**
 * 되돌릴 수 없는 일행 조작의 확인 단계.
 *
 * 일행 목록의 제거·이탈·초대 철회는 모두 목록 안 작은 버튼에서 시작한다.
 * 확인을 같은 자리에 인라인으로 펼치면 어느 줄의 대상인지 흐려지므로, 대상
 * 이름을 제목에 담은 모달로 흐름을 끊는다. 포커스 관리는 공용
 * `useDialogFocus`가 소유한다.
 *
 * 처리 중에는 ESC와 취소를 막는다. 요청이 이미 서버에 가 있는데 화면만 닫으면
 * 사용자는 자기가 취소했다고 생각한다.
 */
export function WaitingPartyConfirmDialog({
  title,
  description,
  confirmLabel,
  submitting,
  onConfirm,
  onCancel,
}: {
  title: string
  description: ReactNode
  confirmLabel: string
  submitting: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const dialogRef = useDialogFocus<HTMLDivElement>({
    onEscape: onCancel,
    escapeEnabled: !submitting,
  })

  return (
    <div
      ref={dialogRef}
      className="waiting-party__dialog"
      role="dialog"
      aria-modal="true"
      aria-label={title}
      tabIndex={-1}
    >
      <h3 className="waiting-party__dialog-title">{title}</h3>
      <div className="waiting-party__dialog-body">{description}</div>
      <div className="waiting-party__dialog-actions">
        <Button variant="ghost" disabled={submitting} onClick={onCancel}>
          취소
        </Button>
        <Button variant="danger" loading={submitting} onClick={onConfirm}>
          {confirmLabel}
        </Button>
      </div>
    </div>
  )
}
