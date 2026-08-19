import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { useManagedStore, useOperatorCatalog, useUpdateStore } from '../api/queries'
import { storeErrorMessage } from '../model/storeErrors'
import {
  validateStoreAddress,
  validateStoreDescription,
  validateStoreName,
  validateTagCodes,
} from '../model/storeValidation'
import {
  OPERATION_STATUS_LABEL,
  REGION_LABEL,
  type EditableOperationStatus,
  type ManagedStore,
  type Region,
  type StoreModes,
  type StoreUpdateRequest,
} from '../model/types'
import { OperatorIcon } from '../../../../app/shells/store-operator/OperatorIcon'
import { PageHeader, SectionCard } from '../../../../app/shells/store-operator/OperatorPage'
import {
  CatalogSelectField,
  CatalogTagPicker,
  ModesFieldset,
  toggleCode,
} from './StoreFormFields'

/**
 * 읽기 전용 값 한 줄.
 *
 * 시안은 서버가 정한 값에 자물쇠를 붙여 회색 상자로 보여 준다. 입력처럼 보이는
 * 비활성 `input`을 두지 않는다. 편집할 수 없는 값은 폼 컨트롤이 아니다.
 */
function ReadonlyRow({ term, value }: { term: string; value: string }) {
  return (
    <div>
      <p className="mi-field__label">{term}</p>
      <p className="op-readonly">
        <span>{value}</span>
        <OperatorIcon name="lock" className="op-icon--sm" />
      </p>
    </div>
  )
}

const REGIONS: readonly Region[] = [
  'SEOUL',
  'BUSAN',
  'DAEGU',
  'DAEJEON',
  'GWANGJU',
]

/**
 * 매장 정보 조회·수정.
 *
 * `PATCH`는 바꾼 필드만 보낸다. 조회 응답에 없는 필드(매장 소개·태그)를 빈 값으로
 * 함께 보내면 서버의 기존 값을 지워 버린다. 그래서 운영자가 실제로 건드린
 * 필드만 본문에 담고, 조회할 수 없는 필드는 그 사실을 화면에 밝힌다.
 */
export function StoreInfoPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)

  const query = useManagedStore(storeId)

  if (query.isPending) {
    return <Loading label="매장 정보를 불러오는 중입니다." />
  }
  if (query.isError) {
    return (
      <ErrorState
        error={query.error}
        message={storeErrorMessage(query.error)}
        onRetry={() => void query.refetch()}
      />
    )
  }

  return <StoreInfoForm storeId={storeId} store={query.data} />
}

interface FormState {
  name: string
  description: string
  region: Region
  address: string
  storeCategoryCode: string
  tagCodes: string[]
  modes: StoreModes
  operationStatus: EditableOperationStatus
}

function initialFormState(store: ManagedStore): FormState {
  return {
    name: store.name,
    description: '',
    region: store.region,
    address: store.address,
    storeCategoryCode: store.storeCategoryCode,
    tagCodes: [],
    modes: { ...store.modes },
    // 폐업(CLOSED)은 이 화면에서 선택할 수 없다. 계약의 편집 가능 값이 둘뿐이다.
    operationStatus:
      store.operationStatus === 'TEMPORARILY_CLOSED'
        ? 'TEMPORARILY_CLOSED'
        : 'OPEN',
  }
}

function StoreInfoForm({
  storeId,
  store,
}: {
  storeId: string
  store: ManagedStore
}) {
  const categories = useOperatorCatalog('store-categories')
  const tags = useOperatorCatalog('store-tags')
  const updateStore = useUpdateStore(storeId)
  const updateKeys = useMemo(createIdempotencyKeyCache, [])

  const [form, setForm] = useState<FormState>(() => initialFormState(store))
  /** 운영자가 실제로 건드린 필드. PATCH 본문은 여기 담긴 것만 포함한다. */
  const [touched, setTouched] = useState<ReadonlySet<keyof FormState>>(
    new Set(),
  )
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  /*
   * 폼은 최초 조회 값으로 한 번만 초기화한다.
   *
   * 응답이 바뀔 때마다 폼을 다시 맞추면 입력 중인 값이 서버 값으로 되돌아간다.
   * 저장에 성공하면 응답이 곧 우리가 보낸 값이므로 다시 맞출 필요도 없다.
   */

  function update<K extends keyof FormState>(field: K, value: FormState[K]) {
    setForm((previous) => ({ ...previous, [field]: value }))
    setTouched((previous) => new Set(previous).add(field))
    setSaved(false)
  }

  function validate(): Record<string, string> {
    const next: Record<string, string> = {}
    const checks: readonly [keyof FormState, string | null][] = [
      ['name', validateStoreName(form.name)],
      ['description', validateStoreDescription(form.description)],
      ['address', validateStoreAddress(form.address)],
      ['tagCodes', validateTagCodes(form.tagCodes)],
    ]
    for (const [field, message] of checks) {
      if (message !== null && touched.has(field)) {
        next[field] = message
      }
    }
    return next
  }

  /** 건드린 필드만 담는다. 빈 본문은 서버가 `minProperties: 1`로 거절한다. */
  function buildBody(): StoreUpdateRequest {
    const body: StoreUpdateRequest = {}
    if (touched.has('name')) {
      body.name = form.name.trim()
    }
    if (touched.has('description')) {
      body.description = form.description
    }
    if (touched.has('region')) {
      body.region = form.region
    }
    if (touched.has('address')) {
      body.address = form.address.trim()
    }
    if (touched.has('storeCategoryCode')) {
      body.storeCategoryCode = form.storeCategoryCode
    }
    if (touched.has('tagCodes')) {
      body.tagCodes = form.tagCodes
    }
    if (touched.has('modes')) {
      body.modes = form.modes
    }
    if (touched.has('operationStatus')) {
      body.operationStatus = form.operationStatus
    }
    return body
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    const nextErrors = validate()
    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    const body = buildBody()
    if (Object.keys(body).length === 0) {
      setFormError('변경한 항목이 없습니다.')
      return
    }

    try {
      await updateStore.mutateAsync({
        body,
        idempotencyKey: updateKeys.keyFor(JSON.stringify(body)),
      })
      setTouched(new Set())
      setSaved(true)
    } catch (error) {
      setFormError(storeErrorMessage(error))
    }
  }

  return (
    <>
      <PageHeader
        title="매장 정보"
        description="공개 정보와 운영 방식을 수정합니다."
        actions={
          <Badge
            tone={store.operationStatus === 'OPEN' ? 'positive' : 'neutral'}
          >
            {OPERATION_STATUS_LABEL[store.operationStatus]}
          </Badge>
        }
      />

      <form onSubmit={handleSubmit} aria-label="매장 정보 수정" noValidate>
        <div className="op-grid op-grid--aside">
          <div className="op-stack">
            {formError !== null && <Alert tone="error" title={formError} />}
            {saved && (
              <Alert tone="info" title="변경 사항을 저장했습니다.">
                <p>공개 매장 정보에 반영됩니다. 기존 예약은 바뀌지 않습니다.</p>
              </Alert>
            )}

            <SectionCard title="매장 기본 정보" icon="store">
              <div className="op-form-grid">
                <TextField
                  label="매장명"
                  required
                  value={form.name}
                  error={errors.name ?? null}
                  onChange={(event) => update('name', event.target.value)}
                />
                <TextField
                  label="매장 소개"
                  value={form.description}
                  help="현재 값을 조회하는 계약이 없습니다. 입력해 저장하면 기존 소개를 대체합니다."
                  error={errors.description ?? null}
                  onChange={(event) => update('description', event.target.value)}
                />
                <div className="op-form-grid op-form-grid--two">
                  <SelectField
                    label="지역"
                    value={form.region}
                    onChange={(event) =>
                      update('region', event.target.value as Region)
                    }
                  >
                    {REGIONS.map((value) => (
                      <option key={value} value={value}>
                        {REGION_LABEL[value]}
                      </option>
                    ))}
                  </SelectField>
                  <SelectField
                    label="운영 상태"
                    value={form.operationStatus}
                    help="폐업 처리는 이 화면에서 하지 않습니다."
                    onChange={(event) =>
                      update(
                        'operationStatus',
                        event.target.value as EditableOperationStatus,
                      )
                    }
                  >
                    <option value="OPEN">영업 중</option>
                    <option value="TEMPORARILY_CLOSED">임시 휴업</option>
                  </SelectField>
                </div>
                <TextField
                  label="매장 주소"
                  required
                  value={form.address}
                  error={errors.address ?? null}
                  onChange={(event) => update('address', event.target.value)}
                />
              </div>
            </SectionCard>

            <SectionCard title="카테고리와 태그" icon="menu-book">
              <div className="op-form-grid">
                <CatalogSelectField
                  label="주 카테고리"
                  value={form.storeCategoryCode}
                  items={categories.data}
                  loading={categories.isPending}
                  onChange={(code) => update('storeCategoryCode', code)}
                />
                <CatalogTagPicker
                  label="매장 태그"
                  hint="현재 선택된 태그를 조회하는 계약이 없습니다. 선택해 저장하면 전체가 대체됩니다."
                  selected={form.tagCodes}
                  items={tags.data}
                  loading={tags.isPending}
                  error={errors.tagCodes ?? null}
                  onToggle={(code) =>
                    update('tagCodes', toggleCode(form.tagCodes, code))
                  }
                />
              </div>
            </SectionCard>
          </div>

          <div className="op-stack">
            <SectionCard
              title="운영 모드"
              icon="store"
              hint="활성화한 방식만 고객 화면에 노출됩니다."
            >
              <ModesFieldset
                value={form.modes}
                onChange={(next) => update('modes', next)}
              />
            </SectionCard>

            {/*
              시안은 저장·취소를 곁 열 맨 아래 별도 패널에 모아 스크롤을 따라
              오게 한다. sticky 처리는 `.op-grid--aside`가 맡는다.
            */}
            <SectionCard title="변경 사항" icon="save">
              <div className="op-actions">
                <Button
                  type="submit"
                  variant="primary"
                  block
                  loading={updateStore.isPending}
                >
                  <OperatorIcon name="save" />
                  변경 사항 저장
                </Button>
              </div>
              <p className="op-section__hint">
                저장하면 공개 매장 정보에 반영되고, 이미 접수된 예약은 바뀌지
                않습니다.
              </p>
            </SectionCard>

            {/* 서버가 정하고 이 화면에서 바꿀 수 없는 값. 시안의 잠금 필드 자리다. */}
            <SectionCard title="등록 정보" icon="lock">
              <div className="op-form-grid">
                <ReadonlyRow term="매장 ID" value={store.storeId} />
                <ReadonlyRow term="시간대" value={store.timeZoneId} />
                <ReadonlyRow term="입점 상태" value={store.verificationStatus} />
              </div>
              <p className="op-section__hint">
                사업자등록번호와 업종은 조회 계약에 없어 표시하지 않습니다.
              </p>
            </SectionCard>
          </div>
        </div>
      </form>
    </>
  )
}
