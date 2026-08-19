const FIXTURE_FIELDS = ['allowedOrigin', 'consumerAccounts', 'storeOperatorAccounts', 'scopes']
const CONSUMER_FIELDS = ['alias', 'emailEnv', 'passwordEnv']
const OPERATOR_FIELDS = ['alias', 'emailEnv', 'passwordEnv', 'storeIds']
const SCOPE_FIELDS = [
  'notificationConsumerAliases',
  'waitingConsumerAliases',
  'waitingStoreOperatorTargets',
]
const STORE_TARGET_FIELDS = ['accountAlias', 'storeId']
const ALIAS_PATTERN = /^[A-Za-z0-9._-]+$/
const STORE_ID_PATTERN = /^[1-9][0-9]*$/
const CURSOR_PATTERN = /^[A-Za-z0-9_-]{1,512}$/

function requireExactFields(name, value, fields) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`)
  }
  const actual = Object.keys(value).sort()
  const expected = [...fields].sort()
  if (actual.length !== expected.length || actual.some((field, index) => field !== expected[index])) {
    throw new Error(`${name} contains an unknown or missing field`)
  }
}

function requireArray(name, value) {
  if (!Array.isArray(value)) throw new Error(`${name} must be an array`)
}

function requireAlias(name, value) {
  if (typeof value !== 'string' || !ALIAS_PATTERN.test(value) || value.length > 100) {
    throw new Error(`${name} alias is invalid`)
  }
}

function requireCredentialReference(name, value, suffix) {
  const pattern = new RegExp(`^K6_[A-Z0-9_]+_${suffix}$`)
  if (typeof value !== 'string' || !pattern.test(value)) {
    throw new Error(`${name} must be an environment variable reference ending in _${suffix}`)
  }
}

function requireStoreId(name, value) {
  if (typeof value !== 'string' || !STORE_ID_PATTERN.test(value)) {
    throw new Error(`${name} storeId must be a positive canonical integer string`)
  }
}

function ensureUnique(name, values) {
  if (new Set(values).size !== values.length) {
    throw new Error(`${name} must not contain a duplicate alias or target`)
  }
}

function indexFixture(value) {
  requireExactFields('fixture', value, FIXTURE_FIELDS)
  if (typeof value.allowedOrigin !== 'string'
    || !/^https:\/\/[^/?#]+$/.test(value.allowedOrigin)) {
    throw new Error('allowedOrigin must be an HTTPS origin without a path')
  }
  requireArray('consumerAccounts', value.consumerAccounts)
  requireArray('storeOperatorAccounts', value.storeOperatorAccounts)
  requireExactFields('scopes', value.scopes, SCOPE_FIELDS)

  const consumers = new Map()
  for (const account of value.consumerAccounts) {
    requireExactFields('consumer account', account, CONSUMER_FIELDS)
    requireAlias('consumer account', account.alias)
    requireCredentialReference('consumer emailEnv', account.emailEnv, 'EMAIL')
    requireCredentialReference('consumer passwordEnv', account.passwordEnv, 'PASSWORD')
    if (consumers.has(account.alias)) throw new Error('consumer account alias is duplicated')
    consumers.set(account.alias, account)
  }

  const operators = new Map()
  for (const account of value.storeOperatorAccounts) {
    requireExactFields('store operator account', account, OPERATOR_FIELDS)
    requireAlias('store operator account', account.alias)
    requireCredentialReference('store operator emailEnv', account.emailEnv, 'EMAIL')
    requireCredentialReference('store operator passwordEnv', account.passwordEnv, 'PASSWORD')
    requireArray('store operator storeIds', account.storeIds)
    if (account.storeIds.length === 0) throw new Error('store operator storeIds is required')
    for (const storeId of account.storeIds) requireStoreId('store operator', storeId)
    ensureUnique('store operator storeIds', account.storeIds)
    if (operators.has(account.alias)) throw new Error('store operator account alias is duplicated')
    operators.set(account.alias, account)
  }

  for (const alias of consumers.keys()) {
    if (operators.has(alias)) throw new Error('account alias cannot be reused across namespace')
  }

  const notificationAliases = value.scopes.notificationConsumerAliases
  const waitingAliases = value.scopes.waitingConsumerAliases
  const operatorTargets = value.scopes.waitingStoreOperatorTargets
  requireArray('notificationConsumerAliases', notificationAliases)
  requireArray('waitingConsumerAliases', waitingAliases)
  requireArray('waitingStoreOperatorTargets', operatorTargets)
  ensureUnique('notificationConsumerAliases', notificationAliases)
  ensureUnique('waitingConsumerAliases', waitingAliases)

  for (const alias of [...notificationAliases, ...waitingAliases]) {
    requireAlias('consumer scope', alias)
    if (!consumers.has(alias)) throw new Error('consumer scope alias is not declared')
  }

  const operatorTargetKeys = []
  for (const target of operatorTargets) {
    requireExactFields('waiting store operator target', target, STORE_TARGET_FIELDS)
    requireAlias('waiting store operator target', target.accountAlias)
    requireStoreId('waiting store operator target', target.storeId)
    const account = operators.get(target.accountAlias)
    if (account === undefined) throw new Error('store operator scope alias is not declared')
    if (!account.storeIds.includes(target.storeId)) {
      throw new Error('store operator scope must reference an owned storeId')
    }
    operatorTargetKeys.push(`${target.accountAlias}:${target.storeId}`)
  }
  ensureUnique('waitingStoreOperatorTargets', operatorTargetKeys)

  if (notificationAliases.length + waitingAliases.length + operatorTargets.length === 0) {
    throw new Error('fixture must select at least one SSE scope')
  }

  return { consumers, operators }
}

export function validateSseFixture(value) {
  indexFixture(value)
  return Object.freeze({ valid: true })
}

export function buildSseTargets(fixture, connectionsPerAccount) {
  indexFixture(fixture)
  if (!Number.isInteger(connectionsPerAccount)
    || connectionsPerAccount <= 0
    || connectionsPerAccount > 6) {
    throw new Error('connectionsPerAccount must be an integer between 1 and 6')
  }

  const grouped = new Map()
  const add = (accountKey, target) => {
    if (!grouped.has(accountKey)) grouped.set(accountKey, [])
    grouped.get(accountKey).push(target)
  }
  for (const accountAlias of fixture.scopes.notificationConsumerAliases) {
    add(`consumer:${accountAlias}`, { kind: 'notification-consumer', audience: 'consumer', accountAlias })
  }
  for (const accountAlias of fixture.scopes.waitingConsumerAliases) {
    add(`consumer:${accountAlias}`, { kind: 'waiting-consumer', audience: 'consumer', accountAlias })
  }
  for (const target of fixture.scopes.waitingStoreOperatorTargets) {
    add(`store-operator:${target.accountAlias}`, {
      kind: 'waiting-store-operator',
      audience: 'store-operator',
      accountAlias: target.accountAlias,
      storeId: target.storeId,
    })
  }

  const targets = []
  for (const accountTargets of grouped.values()) {
    for (let index = 0; index < connectionsPerAccount; index += 1) {
      targets.push(Object.freeze({ ...accountTargets[index % accountTargets.length] }))
    }
  }
  return Object.freeze(targets)
}

export function endpointPath(target) {
  if (target?.kind === 'notification-consumer') {
    return '/api/v1/consumers/me/notification-events'
  }
  if (target?.kind === 'waiting-consumer') {
    return '/api/v1/consumers/me/waiting-events'
  }
  if (target?.kind === 'waiting-store-operator') {
    requireStoreId('waiting store operator target', target.storeId)
    return `/api/v1/store-operators/stores/${target.storeId}/waiting-events`
  }
  throw new Error('endpoint kind is unknown')
}

export function validateChangedEvent(event, endpointKind) {
  if (event === null || typeof event !== 'object' || Array.isArray(event)) {
    throw new Error('SSE event must be an object')
  }
  const expectedName = endpointKind === 'notification-consumer'
    ? 'notifications.changed'
    : endpointKind === 'waiting-consumer' || endpointKind === 'waiting-store-operator'
      ? 'waiting.changed'
      : null
  if (expectedName === null) throw new Error('endpoint kind is unknown')
  if (event.name !== expectedName) throw new Error('SSE event name does not match endpoint kind')
  if (event.data !== '{}') throw new Error('SSE event data must be exactly {}')
  if (typeof event.id !== 'string' || !CURSOR_PATTERN.test(event.id)) {
    throw new Error('SSE event id must be a bounded base64url value')
  }
  return Object.freeze({ valid: true })
}
