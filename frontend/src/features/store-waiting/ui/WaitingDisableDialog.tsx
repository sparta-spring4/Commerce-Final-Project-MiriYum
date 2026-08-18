import { Button } from '../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { useWaitingDisableImpact } from '../api/queries'
import { waitingErrorMessage } from '../model/errors'
import type { WaitingDisableAction } from '../model/types'

/**
 * 비활성화 확인.
 *
 * 계약은 활성 팀이 있으면 `disableAction`을 필수로 만든다(`WAITING_002`).
 * 그래서 이 단계는 안내가 아니라 **선택을 받는 자리**다. 영향 조회가 끝나기 전에는
 * 어떤 선택도 제출할 수 없게 두어, 몇 팀이 영향을 받는지 모르는 채로 일괄 종결이
 * 실행되는 일을 막는다.
 *
 * 되돌릴 수 없는 쪽(일괄 종결)을 기본값으로 두지 않는다.
 */
export function WaitingDisableDialog({
  storeId,
  submitting,
  onConfirm,
  onCancel,
}: {
  storeId: string
  submitting: boolean
  onConfirm: (action: WaitingDisableAction) => void
  onCancel: () => void
}) {
  const impact = useWaitingDisableImpact(storeId, true)

  return (
    <section
      className="mi-card"
      role="group"
      aria-label="웨이팅 비활성화 확인"
    >
      <div className="mi-card__body">
        <div className="op-section__head">
          <div className="op-section__heading">
            <h2 className="op-section__title">웨이팅을 끄기 전에 확인해 주세요</h2>
            <p className="op-section__hint">
              지금 대기 중인 팀을 어떻게 할지 정해야 저장할 수 있습니다.
            </p>
          </div>
        </div>

        {impact.isPending && <Loading label="영향을 확인하는 중입니다." />}

        {impact.isError && (
          <ErrorState
            error={impact.error}
            message={waitingErrorMessage(impact.error)}
            onRetry={() => void impact.refetch()}
          />
        )}

        {impact.isSuccess && (
          <>
            {impact.data.activeTeamCount === 0 ? (
              <Alert tone="info" title="영향을 받는 대기 팀이 없습니다.">
                <p>지금 끄면 신규 접수만 막힙니다.</p>
              </Alert>
            ) : (
              <Alert
                tone="warning"
                title={`현재 ${impact.data.activeTeamCount}팀이 대기 중입니다.`}
              >
                <p>
                  일괄 종결은 되돌릴 수 없습니다. 유지를 선택하면 이미 등록된 팀은
                  그대로 두고 신규 접수만 막습니다.
                </p>
              </Alert>
            )}

            <div className="op-actions">
              <Button
                variant="primary"
                loading={submitting}
                onClick={() => onConfirm('KEEP_ACTIVE')}
              >
                대기 팀 유지하고 끄기
              </Button>
              {/*
                활성 팀이 없으면 종결할 대상이 없다. 누를 수 있게 두면 아무것도
                하지 않는 선택지를 위험한 문구로 제시하게 된다.
              */}
              {impact.data.activeTeamCount > 0 && (
                <Button
                  variant="danger"
                  loading={submitting}
                  onClick={() => onConfirm('CLOSE_ACTIVE_TEAMS')}
                >
                  {`대기 ${impact.data.activeTeamCount}팀 일괄 종결하고 끄기`}
                </Button>
              )}
              <Button variant="ghost" disabled={submitting} onClick={onCancel}>
                취소
              </Button>
            </div>
          </>
        )}
      </div>
    </section>
  )
}
