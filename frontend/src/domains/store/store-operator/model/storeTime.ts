/**
 * 매장 시간대 기준 시각 변환.
 *
 * 계약은 예약 게시 `effectiveAt`과 임시 휴무 `startAt`/`endAt`에 **오프셋을 포함한**
 * RFC 3339 시각을 요구한다. 운영자는 매장 현지 시각으로 입력하므로, 그 현지
 * 시각에 해당하는 오프셋을 매장 `timeZoneId`로 계산해 붙인다.
 *
 * 브라우저 기본 시간대나 서버 기본값으로 추측하지 않는다. 운영자와 서버가 다른
 * 시간대에 있을 때 조용히 몇 시간 어긋나는 것이 이 화면에서 가장 위험한 오류다.
 */

/** `<input type="datetime-local">`이 만드는 값. 초는 없거나 있을 수 있다. */
const LOCAL_DATE_TIME_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/

const MINUTES_PER_HOUR = 60
const MS_PER_MINUTE = 60_000

/**
 * 어떤 순간에 그 시간대가 UTC보다 몇 분 앞서는지 구한다.
 *
 * `timeZoneName: 'longOffset'` 파싱에 의존하지 않는다. 그 표기는 런타임 ICU
 * 구성에 따라 달라진다. 대신 같은 순간을 그 시간대의 벽시계로 포맷해 UTC로
 * 되읽고 차이를 본다. 이 방법은 숫자 필드만 쓰므로 로케일 표기에 흔들리지 않는다.
 */
export function offsetMinutesAt(timeZoneId: string, instant: Date): number {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: timeZoneId,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })

  const parts = new Map(
    formatter.formatToParts(instant).map((part) => [part.type, part.value]),
  )
  const asUtc = Date.UTC(
    Number(parts.get('year')),
    Number(parts.get('month')) - 1,
    Number(parts.get('day')),
    Number(parts.get('hour')),
    Number(parts.get('minute')),
    Number(parts.get('second')),
  )

  // 초 미만 정보는 비교에 필요 없다. 분 단위로 떨어지도록 초를 버린다.
  const instantSeconds = Math.floor(instant.getTime() / 1000) * 1000
  return Math.round((asUtc - instantSeconds) / MS_PER_MINUTE)
}

export function formatOffset(offsetMinutes: number): string {
  const sign = offsetMinutes < 0 ? '-' : '+'
  const absolute = Math.abs(offsetMinutes)
  const hours = String(Math.floor(absolute / MINUTES_PER_HOUR)).padStart(2, '0')
  const minutes = String(absolute % MINUTES_PER_HOUR).padStart(2, '0')
  return `${sign}${hours}:${minutes}`
}

export interface StoreInstant {
  /** 오프셋을 포함한 RFC 3339 문자열. 계약이 요구하는 형태다. */
  iso: string
  /** epoch 밀리초. 미래 시각 검증에 쓴다. */
  epochMs: number
}

/**
 * 매장 현지 벽시계 시각을 오프셋 포함 시각으로 옮긴다.
 *
 * 오프셋을 알려면 순간이 필요하고 순간을 알려면 오프셋이 필요하다. 그래서 입력을
 * 일단 UTC로 읽어 오프셋을 추정한 뒤 그 값으로 순간을 정정한다. 정정한 순간으로
 * 한 번 더 오프셋을 확인해 전환 구간에서도 결과가 안정되게 한다.
 */
export function toStoreInstant(
  timeZoneId: string,
  localDateTime: string,
): StoreInstant | null {
  const matched = LOCAL_DATE_TIME_PATTERN.exec(localDateTime)
  if (matched === null) {
    return null
  }
  const [, year, month, day, hour, minute, second = '00'] = matched
  const naiveUtc = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second),
  )
  if (Number.isNaN(naiveUtc)) {
    return null
  }

  let offsetMinutes = offsetMinutesAt(timeZoneId, new Date(naiveUtc))
  let epochMs = naiveUtc - offsetMinutes * MS_PER_MINUTE
  offsetMinutes = offsetMinutesAt(timeZoneId, new Date(epochMs))
  epochMs = naiveUtc - offsetMinutes * MS_PER_MINUTE

  const iso = `${year}-${month}-${day}T${hour}:${minute}:${second}${formatOffset(offsetMinutes)}`
  return { iso, epochMs }
}

/**
 * 응답의 시각을 매장 시간대 기준으로 표시한다.
 *
 * 24시간 표기를 쓴다. 일부 Node ICU 구성이 ko-KR 오전·오후를 "PM"으로 내보내
 * 화면 문구가 뒤섞인 적이 있다.
 */
export function formatStoreDateTime(
  timeZoneId: string,
  isoDateTime: string,
): string {
  const instant = new Date(isoDateTime)
  if (Number.isNaN(instant.getTime())) {
    return isoDateTime
  }
  const formatter = new Intl.DateTimeFormat('ko-KR', {
    timeZone: timeZoneId,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
  const parts = new Map(
    formatter.formatToParts(instant).map((part) => [part.type, part.value]),
  )
  return `${parts.get('year')}-${parts.get('month')}-${parts.get('day')} ${parts.get('hour')}:${parts.get('minute')}`
}

/** IANA 식별자로 해석 가능한지 확인한다. 주소로 추측하지 않고 입력값을 검증한다. */
export function isSupportedTimeZone(timeZoneId: string): boolean {
  if (timeZoneId.length === 0) {
    return false
  }
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: timeZoneId })
    return true
  } catch {
    return false
  }
}
