import { validateShaEvidence } from '../config.js'
import { assertSafeTarget, parsePositiveInt } from '../lib/safety.js'

const PROFILES = new Set(['smoke', 'reconnect', 'steady', 'slow-client'])
export const SSE_ENDPOINT_KINDS = Object.freeze([
  'notification-consumer',
  'waiting-consumer',
  'waiting-store-operator',
])
const ENDPOINT_KINDS = new Set(SSE_ENDPOINT_KINDS)

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

export function loadSseConfig(env) {
  const targetEnv = requireText('TARGET_ENV', env.TARGET_ENV)
  const baseUrl = requireText('BASE_URL', env.BASE_URL).replace(/\/+$/, '')
  const allowedHosts = parseAllowedHosts(env.ALLOWED_HOSTS)

  // Reject forbidden targets before resolving fixture, evidence, or credential references.
  assertSafeTarget(targetEnv, baseUrl, allowedHosts)

  const profile = requireText('SSE_PROFILE', env.SSE_PROFILE)
  if (!PROFILES.has(profile)) {
    throw new Error('SSE_PROFILE must be smoke, reconnect, steady, or slow-client')
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
    connections: parsePositiveInt('SSE_CONNECTIONS', env.SSE_CONNECTIONS, 200),
    connectionsPerAccount: parsePositiveInt(
      'SSE_CONNECTIONS_PER_ACCOUNT',
      env.SSE_CONNECTIONS_PER_ACCOUNT,
      6,
    ),
    holdDurationSeconds: parsePositiveInt(
      'SSE_HOLD_DURATION_SECONDS',
      env.SSE_HOLD_DURATION_SECONDS,
      600,
    ),
    slowClientDelaySeconds: parsePositiveInt(
      'SSE_SLOW_CLIENT_DELAY_SECONDS',
      env.SSE_SLOW_CLIENT_DELAY_SECONDS,
      30,
    ),
    endpointKinds: parseEndpointKinds(env.SSE_ENDPOINT_KINDS),
    ...shaEvidence,
  })
}
