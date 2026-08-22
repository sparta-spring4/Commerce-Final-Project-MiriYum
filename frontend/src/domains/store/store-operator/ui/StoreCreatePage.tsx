import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { fieldErrorsFromApiError } from '../../../../shared/api/fieldErrors'
import { Button } from '../../../../shared/ui/Button'
import { FieldShell, SelectField, TextField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import { useOperatorCatalog, useSubmitStoreOnboarding } from '../api/queries'
import { storeErrorMessage } from '../model/storeErrors'
import { isSupportedTimeZone } from '../model/storeTime'
import {
  normalizeBusinessNumber,
  validateBusinessNumber,
  validateCatalogSelection,
  validateStoreAddress,
  validateStoreDescription,
  validateStoreName,
  validateTagCodes,
} from '../model/storeValidation'
import { OperatorIcon } from '../../../../app/shells/store-operator/OperatorIcon'
import {
  REGION_LABEL,
  type Region,
  type StoreModes,
} from '../model/types'
import {
  ONBOARDING_EVIDENCE_ACCEPT,
  validateOnboardingEvidence,
} from '../model/onboardingEvidence'
import { PageHeader, SectionCard } from '../../../../app/shells/store-operator/OperatorPage'
import {
  CatalogSelectField,
  CatalogTagPicker,
  ModesFieldset,
  toggleCode,
} from './StoreFormFields'

/**
 * 매장 등록.
 *
 * 접수 성공은 Store 생성 완료가 아니라 신청 접수다. 신청 상태 화면에서 자동검사와
 * 선택적인 플랫폼 심사 결과를 다시 확인하고, storeId가 생긴 뒤에만 관리로 간다.
 *
 * 시간대는 주소나 브라우저 기본값으로 추측하지 않고 운영자가 확인한 IANA
 * 식별자를 제출한다. 자기확약과 필수 약관 동의가 모두 확인되기 전에는 요청을
 * 보내지 않는다.
 */
const TIME_ZONE_OPTIONS = ['Asia/Seoul'] as const

const REGIONS: readonly Region[] = [
  'SEOUL',
  'BUSAN',
  'DAEGU',
  'DAEJEON',
  'GWANGJU',
]

/** 좌측 단계 표시에 쓰는 구역 이름. 카드 제목의 번호와 순서가 같다. */
const FORM_SECTIONS: readonly string[] = [
  '사업자 정보',
  '매장 기본 정보',
  '카테고리와 태그',
  '운영 방식',
  '확인 사항',
]

export function StoreCreatePage() {
  const navigate = useNavigate()
  const categories = useOperatorCatalog('store-categories')
  const tags = useOperatorCatalog('store-tags')
  const submitOnboarding = useSubmitStoreOnboarding()

  /**
   * 멱등 키는 등록 내용에 붙는다.
   *
   * 요청 함수 안에서 만들면 재시도가 새 키를 받아 중복 등록을 막지 못한다.
   * 반대로 입력을 고쳐 다시 제출하는데 이전 키를 쓰면 서버가 `COMMON_007`로
   * 거절한다. 내용 서명으로 키를 정하면 두 규칙이 동시에 지켜진다.
   */
  const evidenceKeys = useMemo(
    () => new WeakMap<File, ReturnType<typeof createIdempotencyKeyCache>>(),
    [],
  )

  const [businessRegistrationNumber, setBusinessRegistrationNumber] =
    useState('')
  const [legalBusinessName, setLegalBusinessName] = useState('')
  const [representativeName, setRepresentativeName] = useState('')
  const [openingDate, setOpeningDate] = useState('')
  const [primaryBusinessCategory, setPrimaryBusinessCategory] = useState('')
  const [primaryBusinessItem, setPrimaryBusinessItem] = useState('')
  const [businessRegistrationEvidence, setBusinessRegistrationEvidence] =
    useState<File | undefined>()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [region, setRegion] = useState<Region>('SEOUL')
  const [address, setAddress] = useState('')
  const [timeZoneId, setTimeZoneId] = useState<string>(TIME_ZONE_OPTIONS[0])
  const [storeCategoryCode, setStoreCategoryCode] = useState('')
  const [tagCodes, setTagCodes] = useState<string[]>([])
  const [modes, setModes] = useState<StoreModes>({
    reservationEnabled: true,
    menuHoldEnabled: false,
    pickupEnabled: false,
  })
  const [applicantSelfAttested, setApplicantSelfAttested] = useState(false)
  const [requiredTermsAgreed, setRequiredTermsAgreed] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  function validate(): Record<string, string> {
    const next: Record<string, string> = {}
    const checks: readonly [string, string | null][] = [
      [
        'businessRegistrationNumber',
        validateBusinessNumber(businessRegistrationNumber),
      ],
      ['name', validateStoreName(name)],
      ['description', validateStoreDescription(description)],
      ['address', validateStoreAddress(address)],
      ['storeCategoryCode', validateCatalogSelection(storeCategoryCode)],
      ['tagCodes', validateTagCodes(tagCodes)],
      [
        'legalBusinessName',
        requiredMessage(legalBusinessName, '법적 상호명을 입력해 주세요.'),
      ],
      [
        'representativeName',
        requiredMessage(representativeName, '대표자명을 입력해 주세요.'),
      ],
      ['openingDate', requiredMessage(openingDate, '개업일을 입력해 주세요.')],
      [
        'primaryBusinessCategory',
        requiredMessage(primaryBusinessCategory, '업태를 입력해 주세요.'),
      ],
      [
        'primaryBusinessItem',
        requiredMessage(primaryBusinessItem, '종목을 입력해 주세요.'),
      ],
      [
        'businessRegistrationEvidence',
        validateOnboardingEvidence(businessRegistrationEvidence),
      ],
      [
        'timeZoneId',
        isSupportedTimeZone(timeZoneId)
          ? null
          : '해석할 수 있는 IANA 시간대 식별자를 입력해 주세요.',
      ],
    ]
    for (const [field, message] of checks) {
      if (message !== null) {
        next[field] = message
      }
    }
    if (!applicantSelfAttested) {
      next.applicantSelfAttested = '사업자 정보 자기확약이 필요합니다.'
    }
    if (!requiredTermsAgreed) {
      next.requiredTermsAgreed = '필수 입점 약관 동의가 필요합니다.'
    }
    return next
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()

    const nextErrors = validate()
    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    // 자기확약과 약관 동의가 확인된 뒤에만 요청을 만든다. 계약이 두 값을
    // 상수 true로 고정하므로 폼 상태를 그대로 흘려보내지 않는다.
    const body = {
      businessRegistrationNumber: normalizeBusinessNumber(
        businessRegistrationNumber,
      ),
      legalBusinessName: legalBusinessName.trim(),
      representativeName: representativeName.trim(),
      openingDate,
      primaryBusinessCategory: primaryBusinessCategory.trim(),
      primaryBusinessItem: primaryBusinessItem.trim(),
      name: name.trim(),
      description,
      region,
      address: address.trim(),
      timeZoneId,
      storeCategoryCode,
      tagCodes,
      modes,
      applicantSelfAttested: true as const,
      requiredTermsAgreed: true as const,
    }

    try {
      const evidence = businessRegistrationEvidence as File
      let keys = evidenceKeys.get(evidence)
      if (keys === undefined) {
        keys = createIdempotencyKeyCache()
        evidenceKeys.set(evidence, keys)
      }
      const application = await submitOnboarding.mutateAsync({
        body,
        evidence,
        idempotencyKey: keys.keyFor(JSON.stringify(body)),
      })
      void navigate(
        fillPath(STORE_OPERATOR_PATHS.onboardingApplication, {
          applicationId: application.applicationId,
        }),
        { replace: true },
      )
    } catch (error) {
      setFormError(storeErrorMessage(error))
      setErrors(mapServerErrors(error))
    }
  }

  return (
    <>
      <PageHeader
        title="매장 등록"
        description="사업자 정보와 등록증을 제출하면 자동 확인이 시작됩니다."
      />

      <form onSubmit={handleSubmit} aria-label="매장 등록" noValidate>
        <div className="op-grid op-grid--intro">
          {/*
            시안 왼쪽의 단계 표시. 실제 다단계 마법사가 아니라 이 폼이 가진
            구역의 목록이다. 진행 중 단계를 꾸며 내지 않고 순서만 알린다.
          */}
          <aside className="op-steps" aria-label="입력 구역">
            <ol className="op-steps__list">
              {FORM_SECTIONS.map((section, index) => (
                <li className="op-step" key={section}>
                  <span className="op-step__index" aria-hidden="true">
                    {index + 1}
                  </span>
                  {section}
                </li>
              ))}
            </ol>
            <p className="op-section__hint">
              신청 접수 후 자동 확인 상태를 확인할 수 있습니다. 승인되기 전에는
              매장이 생성되지 않습니다.
            </p>
          </aside>

          <div className="op-stack">
            {formError !== null && <Alert tone="error" title={formError} />}

          <SectionCard
            title="1. 사업자 정보"
            icon="lock"
            hint="사업자등록증에 적힌 정보와 동일하게 입력해 주세요."
          >
            <div className="op-form-grid">
              <TextField
                label="사업자등록번호"
                required
                inputMode="numeric"
                value={businessRegistrationNumber}
                help="숫자 10자리. 하이픈은 자동으로 제거합니다."
                error={errors.businessRegistrationNumber ?? null}
                onChange={(event) =>
                  setBusinessRegistrationNumber(event.target.value)
                }
              />
              <div className="op-form-grid op-form-grid--two">
                <TextField
                  label="법적 상호명"
                  required
                  value={legalBusinessName}
                  error={errors.legalBusinessName ?? null}
                  onChange={(event) => setLegalBusinessName(event.target.value)}
                />
                <TextField
                  label="대표자명"
                  required
                  value={representativeName}
                  error={errors.representativeName ?? null}
                  onChange={(event) => setRepresentativeName(event.target.value)}
                />
              </div>
              <div className="op-form-grid op-form-grid--two">
                <TextField
                  label="개업일"
                  type="date"
                  required
                  value={openingDate}
                  error={errors.openingDate ?? null}
                  onChange={(event) => setOpeningDate(event.target.value)}
                />
                <TextField
                  label="업태"
                  required
                  value={primaryBusinessCategory}
                  error={errors.primaryBusinessCategory ?? null}
                  onChange={(event) =>
                    setPrimaryBusinessCategory(event.target.value)
                  }
                />
              </div>
              <TextField
                label="종목"
                required
                value={primaryBusinessItem}
                error={errors.primaryBusinessItem ?? null}
                onChange={(event) => setPrimaryBusinessItem(event.target.value)}
              />
              <FieldShell
                label="사업자등록증 파일"
                required
                help="PDF, JPG, PNG · 최대 10MB"
                error={errors.businessRegistrationEvidence ?? null}
              >
                {({ controlId, describedBy, invalid }) => (
                  <input
                    id={controlId}
                    className="mi-field__control"
                    type="file"
                    required
                    accept={ONBOARDING_EVIDENCE_ACCEPT}
                    aria-invalid={invalid || undefined}
                    aria-describedby={describedBy}
                    onChange={(event) => {
                      const file = event.target.files?.[0]
                      setBusinessRegistrationEvidence(file)
                      setErrors((current) => ({
                        ...current,
                        businessRegistrationEvidence:
                          validateOnboardingEvidence(file) ?? '',
                      }))
                    }}
                  />
                )}
              </FieldShell>
            </div>
          </SectionCard>

          <SectionCard title="2. 매장 기본 정보" icon="store">
            <div className="op-form-grid">
              <TextField
                label="매장명"
                required
                value={name}
                error={errors.name ?? null}
                onChange={(event) => setName(event.target.value)}
              />
              <TextField
                label="매장 소개"
                value={description}
                help="최대 1000자"
                error={errors.description ?? null}
                onChange={(event) => setDescription(event.target.value)}
              />
              <div className="op-form-grid op-form-grid--two">
                <SelectField
                  label="지역"
                  required
                  value={region}
                  onChange={(event) => setRegion(event.target.value as Region)}
                >
                  {REGIONS.map((value) => (
                    <option key={value} value={value}>
                      {REGION_LABEL[value]}
                    </option>
                  ))}
                </SelectField>
                <SelectField
                  label="시간대"
                  required
                  value={timeZoneId}
                  help="영업·예약 시각을 해석하는 기준입니다."
                  error={errors.timeZoneId ?? null}
                  onChange={(event) => setTimeZoneId(event.target.value)}
                >
                  {TIME_ZONE_OPTIONS.map((zone) => (
                    <option key={zone} value={zone}>
                      {zone}
                    </option>
                  ))}
                </SelectField>
              </div>
              <TextField
                label="매장 주소"
                required
                value={address}
                error={errors.address ?? null}
                onChange={(event) => setAddress(event.target.value)}
              />
            </div>
          </SectionCard>

          <SectionCard title="3. 카테고리와 태그" icon="menu-book">
            <div className="op-form-grid">
              <CatalogSelectField
                label="주 카테고리"
                required
                value={storeCategoryCode}
                items={categories.data}
                loading={categories.isPending}
                error={errors.storeCategoryCode ?? null}
                onChange={setStoreCategoryCode}
              />
              <CatalogTagPicker
                label="매장 태그"
                hint="승인된 태그만 선택할 수 있습니다. 최대 20개."
                selected={tagCodes}
                items={tags.data}
                loading={tags.isPending}
                error={errors.tagCodes ?? null}
                onToggle={(code) => setTagCodes(toggleCode(tagCodes, code))}
              />
            </div>
          </SectionCard>

          <SectionCard title="4. 운영 방식" icon="list">
            <ModesFieldset value={modes} onChange={setModes} />
          </SectionCard>

          <SectionCard title="5. 확인 사항" icon="check">
            <label className="op-check">
              <input
                type="checkbox"
                checked={applicantSelfAttested}
                onChange={(event) =>
                  setApplicantSelfAttested(event.target.checked)
                }
              />
              <span className="op-check__text">
                입력한 사업자 정보에 대한 책임을 확약합니다.
              </span>
            </label>
            {errors.applicantSelfAttested !== undefined && (
              <p className="mi-field__error">{errors.applicantSelfAttested}</p>
            )}

            <label className="op-check">
              <input
                type="checkbox"
                checked={requiredTermsAgreed}
                onChange={(event) =>
                  setRequiredTermsAgreed(event.target.checked)
                }
              />
              <span className="op-check__text">
                필수 입점 약관에 동의합니다.
              </span>
            </label>
            {errors.requiredTermsAgreed !== undefined && (
              <p className="mi-field__error">{errors.requiredTermsAgreed}</p>
            )}

            <div className="op-actions">
              <Button
                type="submit"
                variant="primary"
                block
                loading={submitOnboarding.isPending}
              >
                <OperatorIcon name="plus" />
                입점 신청
              </Button>
            </div>
          </SectionCard>
          </div>
        </div>
      </form>
    </>
  )
}

/** 서버 필드 경로를 폼 필드 이름으로 옮긴다. 모르는 경로는 무시한다. */
function mapServerErrors(error: unknown): Record<string, string> {
  const known = new Set([
    'businessRegistrationNumber',
    'legalBusinessName',
    'representativeName',
    'openingDate',
    'primaryBusinessCategory',
    'primaryBusinessItem',
    'businessRegistrationEvidence',
    'name',
    'description',
    'region',
    'address',
    'timeZoneId',
    'storeCategoryCode',
    'tagCodes',
  ])
  const mapped: Record<string, string> = {}
  for (const [field, reason] of Object.entries(
    fieldErrorsFromApiError(error),
  )) {
    if (known.has(field)) {
      mapped[field] = reason
    }
  }
  return mapped
}

function requiredMessage(value: string, message: string): string | null {
  return value.trim().length === 0 ? message : null
}
