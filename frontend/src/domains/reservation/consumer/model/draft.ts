import type { components } from '../../../../shared/api/generated/reservation'

export type ReservationCreateRequest =
  components['schemas']['ReservationCreateRequest']
export type ReservationDetail = components['schemas']['ReservationDetail']
export type ReservationRequest = components['schemas']['ReservationRequest']
export type ReservationCreateResult = ReservationDetail | ReservationRequest
export type MenuSelectionRequest =
  components['schemas']['MenuSelectionRequest']

export function isReservationRequest(result: ReservationCreateResult): result is ReservationRequest {
  return 'reservationRequestId' in result
}

/** 계약의 인원 범위. 세 값의 합은 1 이상이어야 한다. */
export const MIN_PARTY_TOTAL = 1
export const MAX_PARTY_PER_TYPE = 100

/** 계약의 menuSelections 상한. */
export const MAX_MENU_SELECTIONS = 20
export const MAX_MENU_QUANTITY = 100

/**
 * 예약 작성 draft.
 *
 * URL search params에 담는다. 연락처 미등록(`ACCOUNT_006`)으로 마이페이지에
 * 다녀와도 입력이 남아 있어야 하고, 뒤로가기와 링크 공유도 유지된다.
 */
export interface ReservationDraft {
  serviceDate: string
  startTime: string
  adultCount: string
  childCount: string
  infantCount: string
  /** menuId → quantity. 수량 0은 선택하지 않은 것이므로 담지 않는다. */
  menuSelections: ReadonlyMap<string, number>
}

const LOCAL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const LOCAL_TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/
const MENU_PARAM = 'menu'

export const EMPTY_DRAFT: ReservationDraft = {
  serviceDate: '',
  startTime: '',
  adultCount: '',
  childCount: '0',
  infantCount: '0',
  menuSelections: new Map(),
}

/**
 * URL에서 draft를 읽는다.
 *
 * 매장 검색에서 넘어온 `partySize`를 성인 인원의 초기값으로 삼는다.
 * 검색의 단일 인원과 예약의 성인·아동·영유아 구성은 다른 개념이므로
 * 나머지 두 값을 추측해 채우지 않고 0으로 둔다.
 *
 * 다만 영유아 동반을 선택한 검색에서는 그 이어받기를 하지 않는다.
 * `carriedPartySize`가 이유를 적어 둔다.
 */
export function readDraft(search: URLSearchParams): ReservationDraft {
  const menuSelections = new Map<string, number>()
  for (const raw of search.getAll(MENU_PARAM)) {
    const separator = raw.lastIndexOf(':')
    if (separator <= 0) {
      continue
    }
    const menuId = raw.slice(0, separator)
    const quantity = Number.parseInt(raw.slice(separator + 1), 10)
    if (
      Number.isInteger(quantity) &&
      quantity >= 1 &&
      quantity <= MAX_MENU_QUANTITY
    ) {
      menuSelections.set(menuId, quantity)
    }
  }

  return {
    serviceDate: matchOrEmpty(search.get('serviceDate'), LOCAL_DATE_PATTERN),
    startTime: matchOrEmpty(search.get('startTime'), LOCAL_TIME_PATTERN),
    adultCount: readCount(search.get('adultCount') ?? carriedPartySize(search)),
    childCount: readCount(search.get('childCount')) || '0',
    infantCount: readCount(search.get('infantCount')) || '0',
    menuSelections,
  }
}

/**
 * 검색의 총 인원을 성인 수로 이어받을지 판정한다.
 *
 * 검색은 `partySize` 하나만 받고 구성은 모른다. `includesInfants=true`면 그
 * 안에 영유아가 몇 명인지 알 수 없어서, 총 인원을 통째로 성인으로 옮기면
 * "3명(영유아 포함)"으로 확인한 가용성이 "성인 3명·영유아 0명" 예약이 된다.
 * 그때는 이어받지 않고 비워 두어 사용자가 구성을 다시 정하게 한다.
 */
function carriedPartySize(search: URLSearchParams): string | null {
  return infantsAnnounced(search) ? null : search.get('partySize')
}

/** 검색에서 영유아 동반을 선택했는지. 예약 화면이 재확인을 요구하는 근거다. */
export function infantsAnnounced(search: URLSearchParams): boolean {
  return search.get('includesInfants') === 'true'
}

function matchOrEmpty(value: string | null, pattern: RegExp): string {
  return value !== null && pattern.test(value) ? value : ''
}

function readCount(value: string | null): string {
  if (value === null) {
    return ''
  }
  const parsed = Number.parseInt(value, 10)
  if (!Number.isInteger(parsed) || parsed < 0 || parsed > MAX_PARTY_PER_TYPE) {
    return ''
  }
  return String(parsed)
}

export function writeDraft(draft: ReservationDraft): URLSearchParams {
  const search = new URLSearchParams()
  if (draft.serviceDate) {
    search.set('serviceDate', draft.serviceDate)
  }
  if (draft.startTime) {
    search.set('startTime', draft.startTime)
  }
  if (draft.adultCount) {
    search.set('adultCount', draft.adultCount)
  }
  search.set('childCount', draft.childCount || '0')
  search.set('infantCount', draft.infantCount || '0')
  for (const [menuId, quantity] of draft.menuSelections) {
    search.append(MENU_PARAM, `${menuId}:${quantity}`)
  }
  return search
}

export function partyTotal(draft: ReservationDraft): number {
  return (
    toCount(draft.adultCount) +
    toCount(draft.childCount) +
    toCount(draft.infantCount)
  )
}

function toCount(value: string): number {
  const parsed = Number.parseInt(value, 10)
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0
}

/** 날짜·시간·인원이 모두 정해졌는지. 메뉴 선택은 선택 사항이다. */
export function isScheduleComplete(draft: ReservationDraft): boolean {
  return (
    LOCAL_DATE_PATTERN.test(draft.serviceDate) &&
    LOCAL_TIME_PATTERN.test(draft.startTime) &&
    partyTotal(draft) >= MIN_PARTY_TOTAL
  )
}

interface ValidateOptions {
  /**
   * 검색에서 영유아 동반을 선택하고 넘어왔는지.
   *
   * 그 조건으로 가용성을 확인했으므로 영유아 0명으로 예약하면 확인한 조건과
   * 실제 예약이 달라진다. 인원 구성을 명시적으로 정하기 전에는 넘어가지 못하게 한다.
   */
  infantsAnnounced?: boolean
}

/** 제출 전 검증. 필드 이름 → 오류 문구. */
export function validateDraft(
  draft: ReservationDraft,
  { infantsAnnounced: announced = false }: ValidateOptions = {},
): Readonly<Record<string, string>> {
  const errors: Record<string, string> = {}

  if (!LOCAL_DATE_PATTERN.test(draft.serviceDate)) {
    errors.serviceDate = '방문 날짜를 선택해 주세요.'
  }
  if (!LOCAL_TIME_PATTERN.test(draft.startTime)) {
    errors.startTime = '방문 시간을 선택해 주세요.'
  }
  if (partyTotal(draft) < MIN_PARTY_TOTAL) {
    errors.adultCount = '방문 인원을 한 명 이상 입력해 주세요.'
  }
  if (announced && toCount(draft.infantCount) < 1) {
    errors.infantCount =
      '검색에서 영유아 동반을 선택했습니다. 영유아 인원을 입력하거나 매장 상세로 돌아가 조건을 바꿔 주세요.'
  }
  if (draft.menuSelections.size > MAX_MENU_SELECTIONS) {
    errors.menuSelections = `메뉴는 최대 ${MAX_MENU_SELECTIONS}종까지 선택할 수 있습니다.`
  }

  return errors
}

/**
 * 예약 생성 요청 본문을 만든다.
 *
 * `endTime`·연락처·사용자 ID는 보내지 않는다. 종료 시각은 서버가 매장 정책으로
 * 계산하고, 연락처와 사용자는 토큰에서 나온다. 스키마가
 * `additionalProperties: false`이므로 임의 필드를 더하면 400이다.
 *
 * 메뉴 선택은 이 한 번의 쓰기에 함께 담는다. 예약 생성 뒤 홀드를 따로
 * 호출하면 두 쓰기 사이에서 수량이 빠져나갈 수 있다.
 */
export function toCreateRequest(
  storeId: string,
  draft: ReservationDraft,
): ReservationCreateRequest {
  const menuSelections: MenuSelectionRequest[] = [...draft.menuSelections]
    .filter(([, quantity]) => quantity > 0)
    .map(([menuId, quantity]) => ({ menuId, quantity }))

  return {
    storeId,
    serviceDate: draft.serviceDate,
    startTime: draft.startTime,
    party: {
      adultCount: toCount(draft.adultCount),
      childCount: toCount(draft.childCount),
      infantCount: toCount(draft.infantCount),
    },
    // 메뉴가 없으면 빈 배열을 보낸다. 별도의 skip API를 만들지 않는다.
    menuSelections,
  }
}

/** 메뉴 선택을 바꾼 새 draft를 만든다. 기존 draft를 변경하지 않는다. */
export function withMenuQuantity(
  draft: ReservationDraft,
  menuId: string,
  quantity: number,
): ReservationDraft {
  const next = new Map(draft.menuSelections)
  if (quantity <= 0) {
    next.delete(menuId)
  } else {
    next.set(menuId, Math.min(quantity, MAX_MENU_QUANTITY))
  }
  return { ...draft, menuSelections: next }
}

/** 메뉴 선택만 비운 새 draft. 메뉴 충돌 뒤 "메뉴 없이 진행"에 쓴다. */
export function withoutMenus(draft: ReservationDraft): ReservationDraft {
  return { ...draft, menuSelections: new Map() }
}

/**
 * 멱등 키를 새로 발급해야 하는 변경인지 판정한다.
 *
 * 같은 입력으로 재시도할 때는 키를 유지해야 backend가 중복 생성을 막는다.
 * 반대로 입력이 달라졌는데 이전 키를 재사용하면 `COMMON_007`로 거절된다.
 */
export function draftSignature(
  storeId: string,
  draft: ReservationDraft,
): string {
  const menus = [...draft.menuSelections]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([menuId, quantity]) => `${menuId}:${quantity}`)
    .join(',')

  return [
    storeId,
    draft.serviceDate,
    draft.startTime,
    draft.adultCount,
    draft.childCount,
    draft.infantCount,
    menus,
  ].join('|')
}
