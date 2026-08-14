function requireObject(value, name) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`)
  }
  return value
}

function requireKeys(value, allowedKeys, requiredKeys, name) {
  const object = requireObject(value, name)
  const keys = Object.keys(object)
  if (keys.some((key) => !allowedKeys.includes(key))) {
    throw new Error(`${name} contains an unsupported field`)
  }
  if (requiredKeys.some((key) => !Object.prototype.hasOwnProperty.call(object, key))) {
    throw new Error(`${name} is missing a required field`)
  }
  return object
}

function requireInteger(name, value, minimum, maximum) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be an integer between ${minimum} and ${maximum}`)
  }
  return value
}

function validateAccount(account) {
  requireKeys(
    account,
    ['alias', 'emailEnv', 'passwordEnv'],
    ['alias', 'emailEnv', 'passwordEnv'],
    'synthetic account',
  )
  if (typeof account.alias !== 'string' || !/^[a-z0-9][a-z0-9-]{0,49}$/.test(account.alias)) {
    throw new Error('synthetic account alias is invalid')
  }
  for (const field of ['emailEnv', 'passwordEnv']) {
    if (typeof account[field] !== 'string' || !/^[A-Z][A-Z0-9_]*$/.test(account[field])) {
      throw new Error(`synthetic account ${field} must reference an environment variable`)
    }
  }
}

function validateReservationTemplate(template, accountAliases) {
  requireKeys(
    template,
    ['accountAlias', 'storeId', 'serviceDate', 'startTime', 'startOffset', 'party', 'menuSelections'],
    ['accountAlias', 'storeId', 'serviceDate', 'startTime', 'party'],
    'reservation template',
  )
  if (!accountAliases.has(template.accountAlias)) {
    throw new Error('reservation template accountAlias is not declared')
  }
  if (typeof template.storeId !== 'string' || !/^[1-9][0-9]*$/.test(template.storeId)) {
    throw new Error('reservation template storeId must be a public ID')
  }
  if (typeof template.serviceDate !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(template.serviceDate)) {
    throw new Error('reservation template serviceDate is invalid')
  }
  if (typeof template.startTime !== 'string' || !/^\d{2}:\d{2}:\d{2}$/.test(template.startTime)) {
    throw new Error('reservation template startTime is invalid')
  }
  if (template.startOffset !== undefined
      && (typeof template.startOffset !== 'string'
        || !/^[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)$/.test(template.startOffset))) {
    throw new Error('reservation template startOffset is invalid')
  }

  const party = requireKeys(
    template.party,
    ['adultCount', 'childCount', 'infantCount'],
    ['adultCount', 'childCount', 'infantCount'],
    'reservation party',
  )
  const partyCounts = ['adultCount', 'childCount', 'infantCount']
    .map((field) => requireInteger(`reservation party ${field}`, party[field], 0, 100))
  if (partyCounts.reduce((sum, count) => sum + count, 0) < 1) {
    throw new Error('reservation party must contain at least one person')
  }

  const menuSelections = template.menuSelections === undefined ? [] : template.menuSelections
  if (!Array.isArray(menuSelections) || menuSelections.length > 20) {
    throw new Error('reservation menuSelections must contain at most 20 items')
  }
  for (const selection of menuSelections) {
    requireKeys(selection, ['menuId', 'quantity'], ['menuId', 'quantity'], 'menu selection')
    if (typeof selection.menuId !== 'string' || !/^[1-9][0-9]*$/.test(selection.menuId)) {
      throw new Error('menu selection menuId must be a public ID')
    }
    requireInteger('menu selection quantity', selection.quantity, 1, 100)
  }
}

export function validateFixture(fixture) {
  requireKeys(
    fixture,
    ['allowedOrigin', 'accounts', 'auth', 'search', 'reservationTemplates', 'notification'],
    ['allowedOrigin', 'accounts', 'auth', 'search', 'reservationTemplates', 'notification'],
    'k6 fixture',
  )
  if (typeof fixture.allowedOrigin !== 'string'
      || !/^https?:\/\/[^/?#]+$/i.test(fixture.allowedOrigin)
      || fixture.allowedOrigin.includes('@')) {
    throw new Error('fixture allowedOrigin must be an HTTP origin')
  }
  if (!Array.isArray(fixture.accounts) || fixture.accounts.length < 2) {
    throw new Error('fixture must contain at least two synthetic accounts')
  }
  fixture.accounts.forEach(validateAccount)
  const aliases = fixture.accounts.map((account) => account.alias)
  const secretRefs = fixture.accounts.flatMap((account) => [account.emailEnv, account.passwordEnv])
  if (new Set(aliases).size !== aliases.length || new Set(secretRefs).size !== secretRefs.length) {
    throw new Error('synthetic account aliases and secret references must be unique')
  }

  requireKeys(fixture.search, ['input'], ['input'], 'search fixture')
  if (typeof fixture.search.input !== 'string'
      || fixture.search.input.trim() === ''
      || fixture.search.input.length > 100) {
    throw new Error('search fixture input must contain 1 to 100 characters')
  }

  if (!Array.isArray(fixture.reservationTemplates) || fixture.reservationTemplates.length < 1) {
    throw new Error('fixture must contain a reservation template')
  }
  const accountAliases = new Set(aliases)

  requireKeys(fixture.auth, ['accountAliases'], ['accountAliases'], 'auth fixture')
  const authAliases = fixture.auth.accountAliases
  if (!Array.isArray(authAliases)
      || authAliases.length < 1
      || new Set(authAliases).size !== authAliases.length
      || authAliases.some((alias) => !accountAliases.has(alias))) {
    throw new Error('auth fixture requires distinct declared account aliases')
  }

  fixture.reservationTemplates.forEach((template) => {
    validateReservationTemplate(template, accountAliases)
  })
  const reservationAliases = new Set(
    fixture.reservationTemplates.map((template) => template.accountAlias),
  )

  requireKeys(
    fixture.notification,
    ['accountAliases', 'pageSize', 'minimumDeliveredItemsPerAccount'],
    ['accountAliases', 'pageSize', 'minimumDeliveredItemsPerAccount'],
    'notification fixture',
  )
  const notificationAliases = fixture.notification.accountAliases
  if (!Array.isArray(notificationAliases)
      || notificationAliases.length < 2
      || new Set(notificationAliases).size !== notificationAliases.length
      || notificationAliases.some((alias) => !accountAliases.has(alias))) {
    throw new Error('notification fixture requires distinct declared account aliases')
  }
  const pageSize = requireInteger('notification pageSize', fixture.notification.pageSize, 1, 50)
  const minimumItems = requireInteger(
    'notification minimumDeliveredItemsPerAccount',
    fixture.notification.minimumDeliveredItemsPerAccount,
    1,
    1000000,
  )
  if (minimumItems <= pageSize) {
    throw new Error('notification fixture must guarantee at least two pages')
  }
  const notificationAliasSet = new Set(notificationAliases)
  if (authAliases.some((alias) => reservationAliases.has(alias) || notificationAliasSet.has(alias))) {
    throw new Error('auth account pool must be isolated from prepared bearer account pools')
  }
  if ([...reservationAliases].some((alias) => notificationAliasSet.has(alias))) {
    throw new Error('reservation and notification account pools must be isolated')
  }
  return fixture
}

export function validateAuthPoolCapacity(fixture, requiredAccounts) {
  if (!Number.isInteger(requiredAccounts) || requiredAccounts < 1) {
    throw new Error('required auth account capacity must be a positive integer')
  }
  if (fixture.auth.accountAliases.length < requiredAccounts) {
    throw new Error(`baseline fixture requires ${requiredAccounts} isolated auth accounts`)
  }
  return fixture
}

export function parseEnvelope(response) {
  requireObject(response, 'response')
  if (typeof response.body !== 'string') {
    throw new Error('response body must be JSON text')
  }

  let envelope
  try {
    envelope = JSON.parse(response.body)
  } catch (_) {
    throw new Error('response body must contain valid JSON')
  }
  requireObject(envelope, 'response envelope')
  for (const field of ['code', 'message', 'data']) {
    if (!Object.prototype.hasOwnProperty.call(envelope, field)) {
      throw new Error(`response envelope must contain ${field}`)
    }
  }
  if (typeof envelope.code !== 'string' || typeof envelope.message !== 'string') {
    throw new Error('response envelope code and message must be strings')
  }
  return {
    code: envelope.code,
    message: envelope.message,
    data: envelope.data,
  }
}

export function classifyStatus(status, expected4xx = []) {
  if (!Number.isInteger(status)) throw new Error('HTTP status must be an integer')
  if (status >= 200 && status < 300) return 'success'
  if (status >= 400 && status < 500) {
    return expected4xx.includes(status) ? 'expected_4xx' : 'unexpected_4xx'
  }
  if (status >= 500 && status < 600) return 'server_5xx'
  return 'unexpected_status'
}

export function recordClassification(classification, tags = {}) {
  const counter = CLASSIFICATION_COUNTERS[classification]
  if (counter !== undefined) counter.add(1, tags)
  return classification
}
import { Counter } from 'k6/metrics'

const CLASSIFICATION_COUNTERS = Object.freeze({
  expected_4xx: new Counter('expected_4xx'),
  unexpected_4xx: new Counter('unexpected_4xx'),
  server_5xx: new Counter('server_5xx'),
  unexpected_status: new Counter('unexpected_status'),
})
