import { Button } from '../../../../shared/ui/Button'
import { Alert } from '../../../../shared/ui/Feedback'
import {
  WAITING_PARTY_GUIDANCE,
  type WaitingPartyErrorView,
} from '../model/partyViewState'

/**
 * 실패한 일행 조작의 안내와 복구 행동.
 *
 * 실패 자리마다 같은 배너를 다시 조립하면 어떤 사유에 재시도를 주는지 화면마다
 * 갈라진다. 사유별 재시도 여부는 안내표가 소유하고 이 컴포넌트가 그대로 따른다.
 * 서버 문구가 있으면 제목에 그대로 쓰고, 없을 때만 기본 문구를 쓴다.
 */
export function WaitingPartyErrorAlert({
  error,
  onRetry,
}: {
  error: WaitingPartyErrorView
  onRetry: () => void
}) {
  const guidance = WAITING_PARTY_GUIDANCE[error.code]

  return (
    <Alert
      tone="error"
      title={error.message ?? guidance.title}
      actions={
        guidance.retryable ? (
          <Button variant="ghost" size="sm" onClick={onRetry}>
            다시 시도
          </Button>
        ) : undefined
      }
    >
      <p>{guidance.description}</p>
    </Alert>
  )
}
