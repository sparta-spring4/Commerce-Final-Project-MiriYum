import { useState } from 'react'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert, EmptyState } from '../../../shared/ui/Feedback'
import { usePlatformOperatorAuth } from '../../platform-operator-auth'
import {
  approveStoreSanction,
  createStoreSanctionImpactPreview,
  releaseStoreSanction,
  type StoreCaseContext,
  type StoreSanction,
} from '../api/storeAdminApi'
import {
  STORE_FEATURE_LABEL,
  STORE_SANCTION_STATUS_LABEL,
  STORE_SANCTION_STATUS_TONE,
  STORE_SANCTION_TYPE_LABEL,
  STORE_SANCTION_TYPE_TONE,
  isReleasableSanction,
} from '../model/storeLabels'
import { ReauthenticationDialog } from './ReauthenticationDialog'
import { sanctionErrorMessage } from './StoreSanctionForm'
import './page.css'

/**
 * 사건에 포함된 제재 목록과 후속 조치.
 *
 * 조치는 두 가지다. 승인 대기 제재의 **추가 승인**과 활성 비영구 제재의 **해제**.
 * 둘 다 재인증이 필수이며, 승인은 최신 영향 미리보기를 함께 요구한다.
 *
 * 영구 퇴점에는 해제 버튼을 그리지 않는다. 계약이 일반 해제를 허용하지 않으므로
 * 버튼을 두면 누를 수 있는데 항상 실패하는 컨트롤이 된다.
 */
export function StoreSanctionList({
  storeId,
  context,
  sanctions,
  canSanction,
  onChanged,
}: {
  storeId: number
  context: StoreCaseContext
  sanctions: readonly StoreSanction[]
  canSanction: boolean
  onChanged: () => void
}) {
  return (
    <section aria-labelledby="sanction-list-heading">
      <h2 className="po-section__title" id="sanction-list-heading">
        사건에 포함된 제재
      </h2>

      {sanctions.length === 0 ? (
        <EmptyState title="아직 제재가 없습니다." />
      ) : (
        <ul className="po-card-list">
          {sanctions.map((sanction) => (
            <li key={sanction.sanctionId} className="po-card">
              <SanctionCard
                storeId={storeId}
                context={context}
                sanction={sanction}
                canSanction={canSanction}
                onChanged={onChanged}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function SanctionCard({
  storeId,
  context,
  sanction,
  canSanction,
  onChanged,
}: {
  storeId: number
  context: StoreCaseContext
  sanction: StoreSanction
  canSanction: boolean
  onChanged: () => void
}) {
  const { apiClient } = usePlatformOperatorAuth()
  const [action, setAction] = useState<'approve' | 'release' | null>(null)
  const [releaseReason, setReleaseReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [idempotencyKey, setIdempotencyKey] = useState(() =>
    createIdempotencyKey(),
  )

  const releasable = isReleasableSanction(sanction.type, sanction.status)
  const approvable = sanction.status === 'PENDING_APPROVAL'

  async function handleApprove(approval: string) {
    setAction(null)
    setBusy(true)
    setError(null)
    try {
      /*
       * 승인도 최신 영향 확인을 요구한다. 제안 시점의 미리보기는 이미
       * 만료됐을 수 있으므로 승인 직전에 다시 계산한다.
       */
      const preview = await createStoreSanctionImpactPreview(apiClient, {
        storeId,
        context,
        body: {
          type: sanction.type,
          restrictedFeatures: sanction.restrictedFeatures,
          ...(sanction.startsAt ? { startsAt: sanction.startsAt } : {}),
          ...(sanction.endsAt ? { endsAt: sanction.endsAt } : {}),
        },
      })
      await approveStoreSanction(apiClient, {
        storeId,
        context,
        sanctionId: sanction.sanctionId,
        idempotencyKey,
        reauthenticationApproval: approval,
        body: {
          expectedSanctionVersion: sanction.sanctionVersion,
          impactConfirmation: {
            previewId: preview.previewId,
            previewDigest: preview.digest,
            caseVersion: preview.caseVersion,
            storeEnforcementVersion: preview.storeEnforcementVersion,
          },
        },
      })
      setIdempotencyKey(createIdempotencyKey())
    } catch (caught) {
      setError(sanctionErrorMessage(caught))
    } finally {
      setBusy(false)
      onChanged()
    }
  }

  async function handleRelease(approval: string) {
    setAction(null)
    setBusy(true)
    setError(null)
    try {
      await releaseStoreSanction(apiClient, {
        storeId,
        context,
        sanctionId: sanction.sanctionId,
        idempotencyKey,
        reauthenticationApproval: approval,
        body: {
          expectedSanctionVersion: sanction.sanctionVersion,
          reason: releaseReason.trim(),
        },
      })
      setIdempotencyKey(createIdempotencyKey())
      setReleaseReason('')
    } catch (caught) {
      setError(sanctionErrorMessage(caught))
    } finally {
      setBusy(false)
      onChanged()
    }
  }

  return (
    <>
      <div className="po-card__header">
        <Badge tone={STORE_SANCTION_TYPE_TONE[sanction.type]}>
          {STORE_SANCTION_TYPE_LABEL[sanction.type]}
        </Badge>
        <Badge tone={STORE_SANCTION_STATUS_TONE[sanction.status]}>
          {STORE_SANCTION_STATUS_LABEL[sanction.status]}
        </Badge>
      </div>

      <dl className="po-detail">
        <div className="po-detail__row">
          <dt>제재 ID</dt>
          <dd>{sanction.sanctionId}</dd>
        </div>
        <div className="po-detail__row">
          <dt>제재 version</dt>
          <dd>{sanction.sanctionVersion}</dd>
        </div>
        <div className="po-detail__row">
          <dt>enforcement version</dt>
          <dd>{sanction.storeEnforcementVersion}</dd>
        </div>
        <div className="po-detail__row">
          <dt>제한 기능</dt>
          <dd>
            {sanction.restrictedFeatures.length === 0
              ? '해당 없음'
              : sanction.restrictedFeatures
                  .map((feature) => STORE_FEATURE_LABEL[feature])
                  .join(', ')}
          </dd>
        </div>
        <div className="po-detail__row">
          <dt>적용 기간</dt>
          <dd>
            {sanction.startsAt ? formatTimestamp(sanction.startsAt) : '즉시'}
            {' ~ '}
            {sanction.endsAt ? formatTimestamp(sanction.endsAt) : '종료 조건 없음'}
          </dd>
        </div>
      </dl>

      {error !== null && <Alert tone="error" title={error} />}

      {sanction.type === 'PERMANENT_EXIT' && (
        <Alert tone="warning" title="영구 퇴점은 해제할 수 없습니다.">
          이 콘솔에는 영구 퇴점을 되돌리는 기능이 없습니다.
        </Alert>
      )}

      {canSanction && approvable && (
        <div className="po-card__actions">
          <p className="po-form__notice">
            제안자와 다른 슈퍼관리자만 승인할 수 있습니다. 승인 직전에 최신 영향을
            다시 계산합니다.
          </p>
          <Button
            type="button"
            variant="primary"
            loading={busy}
            onClick={() => setAction('approve')}
          >
            제재 승인
          </Button>
        </div>
      )}

      {canSanction && releasable && (
        <div className="po-card__actions">
          <TextField
            label="해제 사유"
            name={`releaseReason-${sanction.sanctionId}`}
            value={releaseReason}
            onChange={(event) => setReleaseReason(event.target.value)}
          />
          <Button
            type="button"
            variant="secondary"
            loading={busy}
            disabled={releaseReason.trim().length === 0}
            onClick={() => setAction('release')}
          >
            제재 해제
          </Button>
        </div>
      )}

      {action !== null && (
        <ReauthenticationDialog
          purpose="STORE_SANCTION"
          targetType="STORE"
          targetId={String(storeId)}
          description={
            action === 'approve'
              ? `제재 ${sanction.sanctionId}를 승인합니다. 본인 확인이 필요합니다.`
              : `제재 ${sanction.sanctionId}를 해제합니다. 본인 확인이 필요합니다.`
          }
          onApproved={(approval) =>
            action === 'approve'
              ? void handleApprove(approval)
              : void handleRelease(approval)
          }
          onCancel={() => setAction(null)}
        />
      )}
    </>
  )
}

function formatTimestamp(isoTimestamp: string): string {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) {
    return isoTimestamp
  }
  return parsed.toLocaleString('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short',
  })
}
