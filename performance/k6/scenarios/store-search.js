import { classifyStatus, parseEnvelope, recordClassification } from '../lib/contracts.js'

const REGIONS = new Set(['SEOUL', 'BUSAN', 'DAEGU', 'DAEJEON', 'GWANGJU'])
const OPERATION_STATUSES = new Set(['OPEN', 'TEMPORARILY_CLOSED', 'CLOSED'])
const RESERVATION_AVAILABILITIES = new Set(['NOT_REQUESTED', 'AVAILABLE', 'UNAVAILABLE'])
const WARNING_CODES = new Set([
  'AMBIGUOUS_DICTIONARY_TERM',
  'AMBIGUOUS_PRICE',
  'CONFLICTING_PRICE',
  'INVALID_PARTY_SIZE',
  'CONFLICTING_PARTY_SIZE',
  'AMBIGUOUS_DATE',
  'CONFLICTING_DATE',
  'AMBIGUOUS_TIME',
  'CONFLICTING_TIME',
  'INCOMPLETE_RESERVATION_CONDITION',
  'OUT_OF_RANGE_NUMBER',
])
const WARNING_FIELDS = new Set(['DICTIONARY', 'PRICE', 'PARTY_SIZE', 'DATE', 'TIME', 'RESERVATION'])
const RECOMMENDATION_CODES = new Set([
  'KEYWORD_MATCH',
  'STORE_CATEGORY_MATCH',
  'MENU_PRIMARY_CATEGORY_MATCH',
  'MENU_SECONDARY_CATEGORY_MATCH',
  'TAG_MATCH',
  'AVAILABILITY_MATCH',
  'VISITED_STORE',
  'ORDERED_MENU',
])

function requireExactObject(value, keys, name) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`)
  }
  const actual = Object.keys(value)
  if (actual.length !== keys.length || actual.some((key) => !keys.includes(key))) {
    throw new Error(`${name} must match the OpenAPI fields`)
  }
  return value
}

function requireString(value, name, minimum, maximum) {
  if (typeof value !== 'string' || value.length < minimum || value.length > maximum) {
    throw new Error(`${name} length is outside the OpenAPI bounds`)
  }
  return value
}

function requireEnum(value, allowed, name) {
  if (!allowed.has(value)) throw new Error(`${name} is outside the OpenAPI enum`)
}

function requireCatalogCodes(value, name, validator) {
  if (!Array.isArray(value) || new Set(value).size !== value.length) {
    throw new Error(`${name} must be a unique array`)
  }
  if (value.some((item) => typeof item !== 'string' || !validator(item))) {
    throw new Error(`${name} contains an invalid value`)
  }
}

function isCalendarDate(value) {
  if (typeof value !== 'string') return false
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (match === null) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  if (month < 1 || month > 12) return false
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return day >= 1 && day <= days[month - 1]
}

function validateModes(value) {
  const modes = requireExactObject(
    value,
    ['reservationEnabled', 'menuHoldEnabled', 'pickupEnabled'],
    'store modes',
  )
  if (Object.values(modes).some((enabled) => typeof enabled !== 'boolean')) {
    throw new Error('store modes must be booleans')
  }
}

function validateCoordinates(value) {
  if (value === null) return
  const coordinates = requireExactObject(value, ['latitude', 'longitude'], 'store coordinates')
  if (typeof coordinates.latitude !== 'number'
      || coordinates.latitude < -90
      || coordinates.latitude > 90
      || typeof coordinates.longitude !== 'number'
      || coordinates.longitude < -180
      || coordinates.longitude > 180) {
    throw new Error('store coordinates are outside the OpenAPI bounds')
  }
}

function validateRecommendationReason(value) {
  if (value === null) return
  const reason = requireExactObject(value, ['code', 'message'], 'recommendation reason')
  requireEnum(reason.code, RECOMMENDATION_CODES, 'recommendation reason code')
  requireString(reason.message, 'recommendation reason message', 1, Number.MAX_SAFE_INTEGER)
}

function validateSearchItem(value) {
  const item = requireExactObject(value, [
    'storeId',
    'name',
    'region',
    'address',
    'storeCategoryCode',
    'operationStatus',
    'modes',
    'reservationAvailability',
    'coordinates',
    'recommendationReason',
  ], 'store search item')
  if (typeof item.storeId !== 'string' || !/^[1-9][0-9]*$/.test(item.storeId)) {
    throw new Error('store search item storeId must be a public ID')
  }
  requireString(item.name, 'store search item name', 1, 100)
  requireEnum(item.region, REGIONS, 'store search item region')
  requireString(item.address, 'store search item address', 1, 300)
  if (typeof item.storeCategoryCode !== 'string'
      || !/^[A-Z][A-Z0-9_]{1,49}$/.test(item.storeCategoryCode)) {
    throw new Error('store search item category code is invalid')
  }
  requireEnum(item.operationStatus, OPERATION_STATUSES, 'store search item operation status')
  validateModes(item.modes)
  requireEnum(
    item.reservationAvailability,
    RESERVATION_AVAILABILITIES,
    'store search item reservation availability',
  )
  validateCoordinates(item.coordinates)
  validateRecommendationReason(item.recommendationReason)
}

function validateNormalizedCondition(value) {
  const condition = requireExactObject(value, [
    'regionCodes',
    'storeCategoryCodes',
    'menuCategoryCodes',
    'tagCodes',
    'minimumPrice',
    'maximumPrice',
    'partySize',
    'reservationDate',
    'reservationTime',
    'remainingKeyword',
  ], 'normalized search condition')
  requireCatalogCodes(condition.regionCodes, 'normalized regionCodes', (item) => REGIONS.has(item))
  for (const field of ['storeCategoryCodes', 'menuCategoryCodes', 'tagCodes']) {
    requireCatalogCodes(condition[field], `normalized ${field}`, (item) => (
      /^[A-Z][A-Z0-9_]{1,49}$/.test(item)
    ))
  }
  for (const field of ['minimumPrice', 'maximumPrice']) {
    if (condition[field] !== null
        && (!Number.isSafeInteger(condition[field]) || condition[field] < 0)) {
      throw new Error(`normalized ${field} is invalid`)
    }
  }
  if (condition.partySize !== null
      && (!Number.isInteger(condition.partySize)
        || condition.partySize < 1
        || condition.partySize > 100)) {
    throw new Error('normalized partySize is invalid')
  }
  if (condition.reservationDate !== null
      && !isCalendarDate(condition.reservationDate)) {
    throw new Error('normalized reservationDate is invalid')
  }
  if (condition.reservationTime !== null
      && (typeof condition.reservationTime !== 'string'
        || !/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(condition.reservationTime))) {
    throw new Error('normalized reservationTime is invalid')
  }
  requireString(condition.remainingKeyword, 'normalized remainingKeyword', 0, 100)
}

function validateWarning(value) {
  const warning = requireExactObject(value, ['code', 'field'], 'interpretation warning')
  requireEnum(warning.code, WARNING_CODES, 'interpretation warning code')
  requireEnum(warning.field, WARNING_FIELDS, 'interpretation warning field')
}

function validateSearchData(response) {
  const data = parseEnvelope(response).data
  const search = requireExactObject(data, [
    'items',
    'normalizedCondition',
    'warnings',
    'ruleVersion',
    'vocabularyVersion',
    'rankingRuleVersion',
    'nextCursor',
  ], 'store search data')
  if (!Array.isArray(search.items) || !Array.isArray(search.warnings)) {
    throw new Error('store search items and warnings must be arrays')
  }
  search.items.forEach(validateSearchItem)
  validateNormalizedCondition(search.normalizedCondition)
  search.warnings.forEach(validateWarning)
  requireString(search.ruleVersion, 'store search ruleVersion', 1, Number.MAX_SAFE_INTEGER)
  requireString(search.vocabularyVersion, 'store search vocabularyVersion', 1, Number.MAX_SAFE_INTEGER)
  if (search.rankingRuleVersion !== null && typeof search.rankingRuleVersion !== 'string') {
    throw new Error('store search rankingRuleVersion must be a string or null')
  }
  if (search.nextCursor !== null
      && (typeof search.nextCursor !== 'string' || search.nextCursor.length > 1024)) {
    throw new Error('store search nextCursor is outside the OpenAPI bounds')
  }
}

export function runStoreSearch({ client, baseUrl, search, tags = {} }) {
  if (search === null || typeof search !== 'object') {
    throw new Error('store search fixture is required')
  }
  if (typeof search.input !== 'string' || search.input.trim() === '') {
    throw new Error('store search input is required')
  }

  const requestTagSet = { phase: 'measured', ...tags, request: 'storeSearch' }
  const response = client.get(
    `${baseUrl}/api/v1/stores?searchInput=${encodeURIComponent(search.input)}`,
    { headers: { Accept: 'application/json' }, tags: requestTagSet, redirects: 0 },
  )
  const classification = recordClassification(
    classifyStatus(response.status, [429]),
    requestTagSet,
  )
  if (classification === 'success') validateSearchData(response)

  return { status: response.status, classification }
}
