import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import {
  PageHeader,
  SectionCard,
  SummaryList,
} from '../../../../app/shells/store-operator/OperatorPage'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import {
  useUpdateWaitingSettings,
  useWaitingClosureJob,
  useWaitingSettings,
} from '../api/queries'
import { isStaleVersionConflict, waitingErrorMessage } from '../model/errors'
import {
  ADVANCE_OPEN_MINUTES_MAX,
  ADVANCE_OPEN_MINUTES_MIN,
  isDisabling,
  isUnchanged,
  setAdvanceOpenMinutes,
  setEnabled,
  setReceptionMode,
  toDraft,
  toUpdateRequest,
  validateDraft,
  type WaitingSettingsDraft,
} from '../model/settingsDraft'
import {
  CLOSURE_JOB_STATUS_LABEL,
  RECEPTION_MODES,
  RECEPTION_MODE_HINT,
  RECEPTION_MODE_LABEL,
  isClosureJob,
  isClosureJobSettled,
  needsClosureFollowUp,
  type WaitingClosureJob,
  type WaitingDisableAction,
  type WaitingReceptionMode,
  type WaitingSetting,
} from '../model/types'
import { WaitingDisableDialog } from './WaitingDisableDialog'

/**
 * 웨이팅 사용 설정.
 *
 * 계약의 PUT은 전체 교체이며 `expectedVersion`으로 낙관적 잠금을 건다. 그래서
 * 이 화면은 조회한 설정을 초안으로 복사해 들고 있다가 저장 시점에 그 version을
 * 함께 보낸다. 충돌하면 입력을 지우지 않고 최신 설정만 다시 읽어 비교하게 한다.
 *
 * 시안이 말하는 현장·원격 접수 조건과 웨이팅 운영시간은 활성 계약에 필드가 없다.
 * 없는 입력을 만들어 저장하는 대신 계약이 가진 세 값만 다룬다.
 */
export function WaitingSettingsPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)

  const query = useWaitingSettings(storeId)

  if (query.isPending) {
    return <Loading label="웨이팅 설정을 불러오는 중입니다." />
  }
  if (query.isError) {
    return (
      <ErrorState
        error={query.error}
        message={waitingErrorMessage(query.error)}
        onRetry={() => void query.refetch()}
      />
    )
  }

  /*
   * version이 바뀌어도 폼을 다시 만들지 않는다.
   *
   * 충돌(`WAITING_001`)이 나면 최신 설정을 다시 읽는데, 그때 폼을 remount하면
   * 운영자가 방금 입력한 값이 서버 값으로 되돌아간다. 계약이 요구하는 복구는
   * "입력을 보존한 채 최신 설정을 다시 보여 주는 것"이다. 그래서 초안은 최초
   * 값으로 한 번만 시작하고, 최신 설정은 옆의 요약과 다음 저장의 expectedVersion
   * 으로만 반영한다.
   */
  return <WaitingSettingsForm storeId={storeId} setting={query.data} />
}

function WaitingSettingsForm({
  storeId,
  setting,
}: {
  storeId: string
  setting: WaitingSetting
}) {
  const update = useUpdateWaitingSettings(storeId)
  const idempotencyKeys = useMemo(createIdempotencyKeyCache, [])

  const [draft, setDraft] = useState<WaitingSettingsDraft>(() =>
    toDraft(setting),
  )
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [saved, setSaved] = useState(false)
  /*
   * 202가 준 첫 스냅샷.
   *
   * 표시는 여기서 시작하되 여기서 끝내지 않는다. 아래 query가 같은 jobId로
   * 서버를 다시 읽어 종결까지 따라간다.
   */
  const [startedJob, setStartedJob] = useState<WaitingClosureJob | null>(null)
  const jobQuery = useWaitingClosureJob(
    storeId,
    startedJob?.jobId ?? null,
    startedJob ?? undefined,
  )
  const closureJob = jobQuery.data ?? startedJob

  function edit(next: WaitingSettingsDraft) {
    setDraft(next)
    setSaved(false)
    setFormError(null)
  }

  async function save(disableAction?: WaitingDisableAction) {
    const nextErrors = validateDraft(draft)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      setConfirming(false)
      return
    }

    setFormError(null)
    try {
      const body = toUpdateRequest(draft, setting.version, disableAction)
      const data = await update.mutateAsync({
        body,
        idempotencyKey: idempotencyKeys.keyFor(
          JSON.stringify({
            method: 'PUT',
            path: 'waiting-settings',
            storeId,
            body,
          }),
        ),
      })
      setConfirming(false)
      if (isClosureJob(data)) {
        // 202다. 설정은 꺼졌고 활성 팀 종결은 비동기로 진행된다.
        setStartedJob(data)
      } else {
        setStartedJob(null)
      }
      setSaved(true)
    } catch (error) {
      setConfirming(false)
      setFormError(waitingErrorMessage(error))
      if (isStaleVersionConflict(error)) {
        /*
         * 재조회는 mutation의 onError가 이미 걸었다. 여기서는 입력을 지우지
         * 않는다는 것만 지킨다. 최신 설정이 도착하면 상위가 key로 초안을 다시
         * 시작하므로, 운영자는 바뀐 값을 보고 다시 판단하게 된다.
         */
      }
    }
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (isUnchanged(draft, setting)) {
      setFormError('변경한 항목이 없습니다.')
      return
    }
    if (isDisabling(draft, setting)) {
      // 끄는 변경은 영향 확인을 거친다. 여기서 바로 저장하지 않는다.
      setConfirming(true)
      return
    }
    void save()
  }

  return (
    <>
      <PageHeader
        title="웨이팅 설정"
        description="웨이팅 사용 여부와 접수 방식을 관리합니다."
        actions={
          <Badge tone={setting.enabled ? 'positive' : 'neutral'}>
            {setting.enabled ? '사용 중' : '사용 안 함'}
          </Badge>
        }
      />

      <form onSubmit={handleSubmit} aria-label="웨이팅 설정" noValidate>
        <div className="op-grid op-grid--aside">
          <div className="op-stack">
            {formError !== null && <Alert tone="error" title={formError} />}

            {saved && closureJob == null && (
              <Alert tone="info" title="웨이팅 설정을 저장했습니다." />
            )}

            {jobQuery.isError ? (
              <ErrorState
                error={jobQuery.error}
                message={waitingErrorMessage(jobQuery.error)}
                onRetry={() => void jobQuery.refetch()}
              />
            ) : (
              closureJob != null && <ClosureJobAlert job={closureJob} />
            )}

            {confirming && (
              <WaitingDisableDialog
                storeId={storeId}
                submitting={update.isPending}
                onConfirm={(action) => void save(action)}
                onCancel={() => setConfirming(false)}
              />
            )}

            <SectionCard title="사용 여부" icon="list">
              {/*
                설명은 label 밖에 둔다. label 안의 모든 텍스트가 접근 가능 이름에
                합쳐져 "…사용합니다끄면 신규 접수가…"처럼 읽히고, 레이블로 요소를
                찾는 코드도 함께 흔들린다.
              */}
              <label className="op-check">
                <input
                  type="checkbox"
                  checked={draft.enabled}
                  onChange={(event) =>
                    edit(setEnabled(draft, event.target.checked))
                  }
                />
                <span className="op-check__text">
                  이 매장에서 웨이팅을 사용합니다
                </span>
              </label>
              <p className="op-section__hint">
                끄면 신규 접수가 막히며, 대기 중인 팀 처리 방법을 따로 확인합니다.
              </p>
            </SectionCard>

            <SectionCard title="접수 방식" icon="clock">
              <div className="op-form-grid">
                <SelectField
                  label="접수 모드"
                  value={draft.receptionMode}
                  help={RECEPTION_MODE_HINT[draft.receptionMode]}
                  error={errors.receptionMode ?? null}
                  onChange={(event) =>
                    edit(
                      setReceptionMode(
                        draft,
                        event.target.value as WaitingReceptionMode,
                      ),
                    )
                  }
                >
                  {RECEPTION_MODES.map((mode) => (
                    <option key={mode} value={mode}>
                      {RECEPTION_MODE_LABEL[mode]}
                    </option>
                  ))}
                </SelectField>

                <TextField
                  label="자동 접수 선오픈"
                  type="number"
                  inputMode="numeric"
                  min={ADVANCE_OPEN_MINUTES_MIN}
                  max={ADVANCE_OPEN_MINUTES_MAX}
                  value={String(draft.advanceOpenMinutes)}
                  help={`영업 시작보다 몇 분 먼저 접수를 열지 정합니다. ${ADVANCE_OPEN_MINUTES_MIN}~${ADVANCE_OPEN_MINUTES_MAX}분.`}
                  error={errors.advanceOpenMinutes ?? null}
                  onChange={(event) =>
                    edit(
                      setAdvanceOpenMinutes(
                        draft,
                        Number(event.target.value),
                      ),
                    )
                  }
                />
              </div>
              {draft.receptionMode !== 'AUTO' && (
                <p className="op-section__hint">
                  선오픈 값은 자동 접수일 때만 쓰입니다. 계약이 값을 항상 함께
                  받으므로 지금 입력한 값 그대로 저장됩니다.
                </p>
              )}
            </SectionCard>
          </div>

          <div className="op-stack">
            <SectionCard title="변경 사항" icon="save">
              <div className="op-actions">
                <Button
                  type="submit"
                  variant="primary"
                  block
                  loading={update.isPending && !confirming}
                >
                  설정 저장
                </Button>
              </div>
              <p className="op-section__hint">
                저장은 설정 전체를 교체합니다. 조회한 버전과 서버 버전이 다르면
                거절되고 최신 설정을 다시 보여 드립니다.
              </p>
            </SectionCard>

            <SectionCard title="현재 저장된 값" icon="lock">
              <SummaryList
                items={[
                  { term: '사용 여부', value: setting.enabled ? '사용' : '사용 안 함' },
                  {
                    term: '접수 모드',
                    value: RECEPTION_MODE_LABEL[setting.receptionMode],
                  },
                  {
                    term: '자동 접수 선오픈',
                    value: `${setting.advanceOpenMinutes}분`,
                  },
                  { term: '설정 버전', value: String(setting.version) },
                ]}
              />
            </SectionCard>
          </div>
        </div>
      </form>
    </>
  )
}

/**
 * 일괄 종결 작업 진행 상황.
 *
 * 계약이 주는 것은 집계뿐이다. 팀 단위 결과 목록은 없으므로 완료·실패·대사
 * 대상 수를 그대로 보여 주고, 사람이 손대야 하는 건이 남았는지만 말한다.
 */
function ClosureJobAlert({ job }: { job: WaitingClosureJob }) {
  const settled = isClosureJobSettled(job)
  const followUp = needsClosureFollowUp(job)

  return (
    <Alert
      tone={settled && !followUp ? 'info' : 'warning'}
      title={
        settled
          ? followUp
            ? '일괄 종결이 끝났지만 확인이 필요합니다.'
            : '대기 팀 일괄 종결을 마쳤습니다.'
          : '대기 팀 일괄 종결을 시작했습니다.'
      }
    >
      <p>
        {`상태 ${CLOSURE_JOB_STATUS_LABEL[job.status]} · 대상 ${job.totalTeamCount}팀 · 완료 ${job.completedTeamCount}팀 · 실패 ${job.failedTeamCount}팀 · 대사 필요 ${job.reconciliationRequiredTeamCount}팀`}
      </p>
      <p className="op-section__hint">
        {settled
          ? followUp
            ? '종결하지 못한 팀이 있습니다. 웨이팅 목록에서 해당 팀을 직접 처리해 주세요.'
            : '대상 팀을 모두 종결했습니다.'
          : '종결은 서버에서 이어집니다. 이 화면이 진행 상황을 따라갑니다.'}
      </p>
    </Alert>
  )
}
