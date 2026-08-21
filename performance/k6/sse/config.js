import { validateShaEvidence } from '../config.js'
import { assertSafeTarget, parsePositiveInt } from '../lib/safety.js'

const PROFILES = new Set(['smoke', 'reconnect', 'steady', 'slow-client', 'capacity', 'recovery'])
export const SSE_ENDPOINT_KINDS = Object.freeze([
  'notification-consumer',
  'waiting-consumer',
  'waiting-store-operator',
])
const ENDPOINT_KINDS = new Set(SSE_ENDPOINT_KINDS)

export function requiredSmokeEndpointKinds(profile, endpointKinds) {
  return profile === 'recovery' ? SSE_ENDPOINT_KINDS : endpointKinds
}

function requireText(name, rawValue) {
  if (typeof rawValue !== 'string' || rawValue.trim() === '') {
    throw new Error(`${name} is required`)
  }
  return rawValue.trim()
}

function requireJsonPath(name, rawValue) {
  const path = requireText(name, rawValue)
  if (!path.endsWith('.json')) {
    throw new Error(`${name} must reference a JSON file`)
  }
  return path
}

function requireRunId(rawValue) {
  const runId = requireText('SSE_RUN_ID', rawValue)
  if (runId.length > 100 || !/^[A-Za-z0-9._-]+$/.test(runId)) {
    throw new Error('SSE_RUN_ID must contain only safe identifier characters')
  }
  return runId
}

function parseAllowedHosts(rawValue) {
  const hosts = requireText('ALLOWED_HOSTS', rawValue)
    .split(',')
    .map((host) => host.trim().toLowerCase())
    .filter(Boolean)
  if (hosts.length === 0 || new Set(hosts).size !== hosts.length) {
    throw new Error('ALLOWED_HOSTS must contain unique hosts')
  }
  return hosts
}

function parseEndpointKinds(rawValue) {
  const kinds = requireText('SSE_ENDPOINT_KINDS', rawValue)
    .split(',')
    .map((kind) => kind.trim())
    .filter(Boolean)
  if (kinds.length === 0
    || kinds.some((kind) => !ENDPOINT_KINDS.has(kind))
    || new Set(kinds).size !== kinds.length) {
    throw new Error('SSE_ENDPOINT_KINDS must contain known unique endpoint kinds')
  }
  return Object.freeze(kinds)
}

function requireUuid(name, rawValue) {
  const value = requireText(name, rawValue).toLowerCase()
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)) {
    throw new Error(`${name} must be a standard UUID`)
  }
  return value
}

export function loadSseConfig(env) {
  const targetEnv = requireText('TARGET_ENV', env.TARGET_ENV)
  const baseUrl = requireText('BASE_URL', env.BASE_URL).replace(/\/+$/, '')
  const allowedHosts = parseAllowedHosts(env.ALLOWED_HOSTS)

  // Reject forbidden targets before resolving fixture, evidence, or credential references.
  assertSafeTarget(targetEnv, baseUrl, allowedHosts)

  const profile = requireText('SSE_PROFILE', env.SSE_PROFILE)
  if (!PROFILES.has(profile)) {
    throw new Error('SSE_PROFILE must be smoke, reconnect, steady, slow-client, capacity, or recovery')
  }
  if (targetEnv === 'staging' && env.STAGING_APPROVED !== 'true') {
    throw new Error('staging SSE execution requires STAGING_APPROVED=true')
  }

  const shaEvidence = validateShaEvidence({
    targetEnv,
    commitSha: env.COMMIT_SHA,
    harnessCommitSha: env.HARNESS_COMMIT_SHA,
    stagingSplitApproved: env.STAGING_SPLIT_SHA_APPROVED === 'true',
    harnessSourceVerified: env.STAGING_HARNESS_SOURCE_VERIFIED === 'true',
  })

  const connections = parsePositiveInt('SSE_CONNECTIONS', env.SSE_CONNECTIONS, 200)
  const endpointKinds = parseEndpointKinds(env.SSE_ENDPOINT_KINDS)
  const httpProbeRate = profile === 'smoke' || profile === 'recovery'
    ? null
    : parsePositiveInt('SSE_HTTP_PROBE_RATE', env.SSE_HTTP_PROBE_RATE, 100)
  const httpMaxP95Ratio = profile === 'smoke' || profile === 'recovery'
    ? null
    : parsePositiveInt('SSE_HTTP_MAX_P95_RATIO', env.SSE_HTTP_MAX_P95_RATIO, 10)
  const holdDurationSeconds = parsePositiveInt(
    'SSE_HOLD_DURATION_SECONDS',
    env.SSE_HOLD_DURATION_SECONDS,
    600,
  )
  const slowClientDelaySeconds = parsePositiveInt(
    'SSE_SLOW_CLIENT_DELAY_SECONDS',
    env.SSE_SLOW_CLIENT_DELAY_SECONDS,
    60,
  )

  let slowClientConnections = null
  let slowClientIdempotencyKey = null
  let slowClientMaxCleanupSeconds = null
  let companionMinLifetimeSeconds = null
  let recoveryArmDelaySeconds = null
  let recoveryMaxSeconds = null
  let recoveryTriggerIdempotencyKey = null
  let recoveryCleanupIdempotencyKey = null
  if (profile === 'slow-client') {
    if (endpointKinds.length !== 1 || endpointKinds[0] !== 'waiting-store-operator') {
      throw new Error('slow-client requires only waiting-store-operator')
    }
    if (connections < 2) {
      throw new Error('slow-client requires slow and companion connections')
    }
    slowClientConnections = parsePositiveInt(
      'SSE_SLOW_CLIENT_CONNECTIONS',
      env.SSE_SLOW_CLIENT_CONNECTIONS,
      connections - 1,
    )
    if (slowClientConnections !== connections - 1) {
      throw new Error('slow-client requires exactly one companion connection')
    }
    if (env.SSE_SLOW_CLIENT_TRIGGER_APPROVED !== 'true') {
      throw new Error('slow-client requires SSE_SLOW_CLIENT_TRIGGER_APPROVED=true')
    }
    slowClientIdempotencyKey = requireUuid(
      'SSE_SLOW_CLIENT_IDEMPOTENCY_KEY',
      env.SSE_SLOW_CLIENT_IDEMPOTENCY_KEY,
    )
    slowClientMaxCleanupSeconds = parsePositiveInt(
      'SSE_SLOW_CLIENT_MAX_CLEANUP_SECONDS',
      env.SSE_SLOW_CLIENT_MAX_CLEANUP_SECONDS,
      600,
    )
    companionMinLifetimeSeconds = parsePositiveInt(
      'SSE_COMPANION_MIN_LIFETIME_SECONDS',
      env.SSE_COMPANION_MIN_LIFETIME_SECONDS,
      600,
    )
    if (slowClientDelaySeconds <= 30
      || slowClientDelaySeconds >= slowClientMaxCleanupSeconds
      || slowClientMaxCleanupSeconds >= companionMinLifetimeSeconds
      || companionMinLifetimeSeconds >= holdDurationSeconds) {
      throw new Error('slow-client timing windows must be strictly ordered')
    }
  }
  if (profile === 'recovery') {
    if (connections !== 1
      || endpointKinds.length !== 1
      || endpointKinds[0] !== 'waiting-store-operator') {
      throw new Error('recovery requires one waiting-store-operator connection')
    }
    if (env.SSE_RECOVERY_TRIGGER_APPROVED !== 'true') {
      throw new Error('recovery requires SSE_RECOVERY_TRIGGER_APPROVED=true')
    }
    recoveryArmDelaySeconds = parsePositiveInt(
      'SSE_RECOVERY_ARM_DELAY_SECONDS', env.SSE_RECOVERY_ARM_DELAY_SECONDS, 60,
    )
    recoveryMaxSeconds = parsePositiveInt(
      'SSE_RECOVERY_MAX_SECONDS', env.SSE_RECOVERY_MAX_SECONDS, 60,
    )
    recoveryTriggerIdempotencyKey = requireUuid(
      'SSE_RECOVERY_TRIGGER_IDEMPOTENCY_KEY', env.SSE_RECOVERY_TRIGGER_IDEMPOTENCY_KEY,
    )
    recoveryCleanupIdempotencyKey = requireUuid(
      'SSE_RECOVERY_CLEANUP_IDEMPOTENCY_KEY', env.SSE_RECOVERY_CLEANUP_IDEMPOTENCY_KEY,
    )
    if (recoveryTriggerIdempotencyKey === recoveryCleanupIdempotencyKey) {
      throw new Error('recovery trigger and cleanup idempotency keys must differ')
    }
  }

  const connectionsPerAccount = parsePositiveInt(
    'SSE_CONNECTIONS_PER_ACCOUNT',
    env.SSE_CONNECTIONS_PER_ACCOUNT,
    profile === 'capacity' ? 7 : 6,
  )
  if (profile === 'capacity'
    && (endpointKinds.length !== 1
      || connections !== connectionsPerAccount
      || connectionsPerAccount <= 6)) {
    throw new Error(
      'capacity requires one endpoint kind and exactly 7 connections for one account',
    )
  }

  return Object.freeze({
    targetEnv,
    baseUrl,
    allowedHosts: Object.freeze(allowedHosts),
    profile,
    fixturePath: requireJsonPath('SSE_FIXTURE_PATH', env.SSE_FIXTURE_PATH),
    runId: requireRunId(env.SSE_RUN_ID),
    smokeProofPath: profile === 'smoke'
      ? null
      : requireJsonPath('SSE_SMOKE_PROOF_PATH', env.SSE_SMOKE_PROOF_PATH),
    connections,
    connectionsPerAccount,
    holdDurationSeconds,
    slowClientDelaySeconds,
    endpointKinds,
    httpProbeRate,
    httpMaxP95Ratio,
    slowClientConnections,
    slowClientIdempotencyKey,
    slowClientMaxCleanupSeconds,
    companionMinLifetimeSeconds,
    recoveryArmDelaySeconds,
    recoveryMaxSeconds,
    recoveryTriggerIdempotencyKey,
    recoveryCleanupIdempotencyKey,
    ...shaEvidence,
  })
}
