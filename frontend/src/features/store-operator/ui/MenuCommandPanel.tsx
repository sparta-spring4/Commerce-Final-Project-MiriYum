import { useMemo, useState } from 'react'
import { createIdempotencyKeyCache } from '../../../shared/api/idempotencyKey'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { SelectField, TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import {
  useCancelMenuPublication,
  useChangeMenuSellingStatus,
  useChangeMenuVisibility,
  usePublishMenu,
  useRetireMenu,
} from '../api/menuQueries'
import { storeErrorMessage } from '../model/storeErrors'
import { formatStoreDateTime } from '../model/storeTime'
import { validateChangeReason } from '../model/storeValidation'
import {
  MENU_SELLING_STATUS_LABEL,
  MENU_VERSION_STATUS_LABEL,
  MENU_VISIBILITY_LABEL,
  type ManagedMenu,
  type MenuSellingStatus,
  type MenuVisibility,
} from '../model/types'
import { SectionCard, SummaryList } from './PageHeader'
import { PublicationControls } from './PublicationControls'

const VISIBILITIES: readonly MenuVisibility[] = ['VISIBLE', 'HIDDEN']
const SELLING_STATUSES: readonly MenuSellingStatus[] = [
  'SELLING',
  'SOLD_OUT',
  'PAUSED',
]

/**
 * 메뉴 상태 명령.
 *
 * 범용 status PATCH는 없다. 게시·게시 취소·운영 종료·노출·판매는 각자 endpoint를
 * 가지며 전부 변경 사유를 요구한다. 그래서 명령마다 사유 입력과 멱등 키를 따로 둔다.
 */
export function MenuCommandPanel({
  storeId,
  menu,
  timeZoneId,
}: {
  storeId: string
  menu: ManagedMenu
  timeZoneId: string
}) {
  const publish = usePublishMenu(storeId, menu.menuId)
  const cancelPublication = useCancelMenuPublication(storeId, menu.menuId)
  const retire = useRetireMenu(storeId, menu.menuId)
  const changeVisibility = useChangeMenuVisibility(storeId, menu.menuId)
  const changeSelling = useChangeMenuSellingStatus(storeId, menu.menuId)

  const publishKeys = useMemo(createIdempotencyKeyCache, [])
  const cancelKeys = useMemo(createIdempotencyKeyCache, [])
  const retireKeys = useMemo(createIdempotencyKeyCache, [])
  const visibilityKeys = useMemo(createIdempotencyKeyCache, [])
  const sellingKeys = useMemo(createIdempotencyKeyCache, [])

  const [visibility, setVisibility] = useState<MenuVisibility>(menu.visibility)
  const [visibilityReason, setVisibilityReason] = useState('')
  const [sellingStatus, setSellingStatus] = useState<MenuSellingStatus>(
    menu.sellingStatus,
  )
  const [sellingReason, setSellingReason] = useState('')
  const [retireReason, setRetireReason] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [commandError, setCommandError] = useState<string | null>(null)

  async function run(action: () => Promise<unknown>) {
    setCommandError(null)
    try {
      await action()
    } catch (error) {
      setCommandError(storeErrorMessage(error))
    }
  }

  function withReason(
    field: string,
    reason: string,
    action: () => Promise<unknown>,
  ) {
    const reasonError = validateChangeReason(reason)
    if (reasonError !== null) {
      setErrors({ [field]: reasonError })
      return
    }
    setErrors({})
    void run(action)
  }

  if (menu.retired) {
    return (
      <SectionCard title="운영 종료된 메뉴">
        <Alert tone="info" title="이 메뉴는 운영을 종료했습니다.">
          <p>
            종료한 메뉴는 다시 게시하지 않습니다. 기존 예약에 담긴 메뉴 스냅샷은
            그대로 유지됩니다.
          </p>
        </Alert>
      </SectionCard>
    )
  }

  return (
    <div className="op-stack">
      {commandError !== null && <Alert tone="error" title={commandError} />}

      <SectionCard title="버전 상태">
        <SummaryList
          items={[
            {
              term: '게시된 버전',
              value: describeVersion(menu.published, timeZoneId),
            },
            {
              term: '게시 예약',
              value: describeVersion(menu.scheduled, timeZoneId),
            },
            { term: '초안', value: describeVersion(menu.draft, timeZoneId) },
          ]}
        />
      </SectionCard>

      <SectionCard title="게시" hint="초안을 저장한 뒤 게시 시점을 정합니다.">
        <PublicationControls
          version={menu.draft?.versionNumber ?? null}
          timeZoneId={timeZoneId}
          canCancelPublication={menu.scheduled != null}
          publishing={publish.isPending}
          cancelling={cancelPublication.isPending}
          onPublish={(submission) => {
            // 메뉴 계약은 필드 이름이 `mode`다. 스케줄 계약과 이름이 달라
            // 여기서 명시적으로 옮긴다.
            const body =
              submission.publicationMode === 'SCHEDULED'
                ? {
                    mode: 'SCHEDULED' as const,
                    effectiveAt: submission.effectiveAt,
                    changeReason: submission.changeReason,
                  }
                : {
                    mode: 'IMMEDIATE' as const,
                    changeReason: submission.changeReason,
                  }
            void run(() =>
              publish.mutateAsync({
                body,
                idempotencyKey: publishKeys.keyFor(JSON.stringify(body)),
              }),
            )
          }}
          onCancelPublication={(changeReason) => {
            const body = { changeReason }
            void run(() =>
              cancelPublication.mutateAsync({
                body,
                idempotencyKey: cancelKeys.keyFor(JSON.stringify(body)),
              }),
            )
          }}
        />
      </SectionCard>

      <SectionCard
        title="노출 상태"
        hint={`현재 ${MENU_VISIBILITY_LABEL[menu.visibility]}. 게시 여부와는 다른 축입니다.`}
      >
        <SelectField
          label="노출"
          value={visibility}
          onChange={(event) =>
            setVisibility(event.target.value as MenuVisibility)
          }
        >
          {VISIBILITIES.map((value) => (
            <option key={value} value={value}>
              {MENU_VISIBILITY_LABEL[value]}
            </option>
          ))}
        </SelectField>
        <TextField
          label="노출 변경 사유"
          value={visibilityReason}
          error={errors.visibilityReason ?? null}
          onChange={(event) => setVisibilityReason(event.target.value)}
        />
        <div className="op-actions">
          <Button
            variant="ghost"
            loading={changeVisibility.isPending}
            onClick={() => {
              const body = { visibility, changeReason: visibilityReason }
              withReason('visibilityReason', visibilityReason, () =>
                changeVisibility.mutateAsync({
                  body,
                  idempotencyKey: visibilityKeys.keyFor(JSON.stringify(body)),
                }),
              )
            }}
          >
            노출 상태 변경
          </Button>
        </div>
      </SectionCard>

      <SectionCard
        title="판매 상태"
        hint={`현재 ${MENU_SELLING_STATUS_LABEL[menu.sellingStatus]}. 제공 구간 재고와는 다른 축입니다.`}
      >
        <SelectField
          label="판매"
          value={sellingStatus}
          onChange={(event) =>
            setSellingStatus(event.target.value as MenuSellingStatus)
          }
        >
          {SELLING_STATUSES.map((value) => (
            <option key={value} value={value}>
              {MENU_SELLING_STATUS_LABEL[value]}
            </option>
          ))}
        </SelectField>
        <TextField
          label="판매 변경 사유"
          value={sellingReason}
          error={errors.sellingReason ?? null}
          onChange={(event) => setSellingReason(event.target.value)}
        />
        <div className="op-actions">
          <Button
            variant="ghost"
            loading={changeSelling.isPending}
            onClick={() => {
              const body = { sellingStatus, changeReason: sellingReason }
              withReason('sellingReason', sellingReason, () =>
                changeSelling.mutateAsync({
                  body,
                  idempotencyKey: sellingKeys.keyFor(JSON.stringify(body)),
                }),
              )
            }}
          >
            판매 상태 변경
          </Button>
        </div>
      </SectionCard>

      <SectionCard
        title="운영 종료"
        hint="종료하면 다시 게시할 수 없습니다. 기존 예약의 메뉴 스냅샷은 유지됩니다."
      >
        <TextField
          label="운영 종료 사유"
          value={retireReason}
          error={errors.retireReason ?? null}
          onChange={(event) => setRetireReason(event.target.value)}
        />
        <div className="op-actions">
          <Button
            variant="danger"
            loading={retire.isPending}
            onClick={() => {
              const body = { changeReason: retireReason }
              withReason('retireReason', retireReason, () =>
                retire.mutateAsync({
                  body,
                  idempotencyKey: retireKeys.keyFor(JSON.stringify(body)),
                }),
              )
            }}
          >
            메뉴 운영 종료
          </Button>
        </div>
      </SectionCard>
    </div>
  )
}

function describeVersion(
  version: ManagedMenu['published'],
  timeZoneId: string,
): React.ReactNode {
  if (version == null) {
    return '없음'
  }
  const effective =
    version.effectiveAt == null
      ? null
      : formatStoreDateTime(timeZoneId, version.effectiveAt)

  return (
    <>
      <Badge tone={version.status === 'PUBLISHED' ? 'positive' : 'neutral'}>
        {`${MENU_VERSION_STATUS_LABEL[version.status]} v${version.versionNumber}`}
      </Badge>
      {effective !== null && ` · ${effective}`}
    </>
  )
}
