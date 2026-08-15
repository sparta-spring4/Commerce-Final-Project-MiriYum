import { assertSafeTarget, parsePositiveInt } from './lib/safety.js'

const VALID_PROFILES = new Set(['smoke', 'local-baseline', 'staging-baseline'])
export const SCENARIO_NAMES = Object.freeze([
  'authRefresh',
  'storeSearch',
  'reservationCreate',
  'notificationHistory',
])
const VALID_SCENARIOS = new Set(SCENARIO_NAMES)
const AUTH_MAX_VUS = 50
const HARD_LIMITS = Object.freeze({
  maxVus: 100,
  durationSeconds: 600,
  arrivalRate: 1000,
})

function requireText(name, rawValue) {
  if (typeof rawValue !== 'string' || rawValue.trim() === '') {
    throw new Error(`${name} is required`)
  }
  return rawValue.trim()
}

function requireRunId(rawValue) {
  const runId = requireText('RUN_ID', rawValue)
  if (runId.length > 100 || !/^[A-Za-z0-9._-]+$/.test(runId)) {
    throw new Error('RUN_ID must contain only safe identifier characters')
  }
  return runId
}

function requireCommitSha(rawValue) {
  const commitSha = requireText('COMMIT_SHA', rawValue).toLowerCase()
  if (!/^[0-9a-f]{40}$/.test(commitSha)) {
    throw new Error('COMMIT_SHA must be a full 40-character Git SHA')
  }
  return commitSha
}

function requireJsonPath(name, rawValue) {
  const path = requireText(name, rawValue)
  if (!path.endsWith('.json')) {
    throw new Error(`${name} must reference a JSON file`)
  }
  return path
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

function loadLimits(profile, env) {
  if (profile === 'smoke') {
    return Object.freeze({ maxVus: 1, durationSeconds: 1, arrivalRate: 1 })
  }
  return Object.freeze({
    maxVus: parsePositiveInt('MAX_VUS', env.MAX_VUS, HARD_LIMITS.maxVus),
    durationSeconds: parsePositiveInt(
      'DURATION_SECONDS',
      env.DURATION_SECONDS,
      HARD_LIMITS.durationSeconds,
    ),
    arrivalRate: parsePositiveInt(
      'ARRIVAL_RATE',
      env.ARRIVAL_RATE,
      HARD_LIMITS.arrivalRate,
    ),
  })
}

function loadScenarioNames(rawValue) {
  const names = rawValue === undefined || rawValue.trim() === ''
    ? [...SCENARIO_NAMES]
    : rawValue.split(',').map((name) => name.trim()).filter(Boolean)
  if (names.length === 0 || names.some((name) => !VALID_SCENARIOS.has(name))) {
    throw new Error('SCENARIOS contains an unknown scenario')
  }
  if (new Set(names).size !== names.length) {
    throw new Error('SCENARIOS must not contain duplicates')
  }
  return Object.freeze(names)
}

export function loadConfig(env) {
  const targetEnv = requireText('TARGET_ENV', env.TARGET_ENV)
  const baseUrl = requireText('BASE_URL', env.BASE_URL).replace(/\/+$/, '')
  const allowedHosts = parseAllowedHosts(env.ALLOWED_HOSTS)
  const profile = requireText('PROFILE', env.PROFILE)
  if (!VALID_PROFILES.has(profile)) {
    throw new Error('PROFILE must be smoke, local-baseline, or staging-baseline')
  }

  if (targetEnv === 'staging' && env.STAGING_APPROVED !== 'true') {
    throw new Error('staging execution requires STAGING_APPROVED=true')
  }
  assertSafeTarget(targetEnv, baseUrl, allowedHosts)
  if (profile === 'local-baseline' && targetEnv !== 'local') {
    throw new Error('local-baseline requires TARGET_ENV=local')
  }
  if (profile === 'staging-baseline' && targetEnv !== 'staging') {
    throw new Error('staging-baseline requires TARGET_ENV=staging')
  }
  const prerequisiteSmokeRunId = profile === 'local-baseline'
    ? requireRunId(env.LOCAL_SMOKE_RUN_ID)
    : profile === 'staging-baseline'
      ? requireRunId(env.STAGING_SMOKE_RUN_ID)
      : null
  const smokeProofPath = profile === 'smoke'
    ? null
    : requireJsonPath('SMOKE_PROOF_PATH', env.SMOKE_PROOF_PATH)

  const fixturePath = requireJsonPath('FIXTURE_PATH', env.FIXTURE_PATH)

  const limits = loadLimits(profile, env)
  const scenarioNames = loadScenarioNames(env.SCENARIOS)
  if (profile !== 'smoke' && limits.maxVus < scenarioNames.length) {
    throw new Error('MAX_VUS must cover every selected scenario')
  }
  if (profile !== 'smoke' && limits.arrivalRate < scenarioNames.length) {
    throw new Error('ARRIVAL_RATE must cover every selected scenario')
  }
  if (profile !== 'smoke'
    && scenarioNames.includes('authRefresh')
    && limits.maxVus > AUTH_MAX_VUS) {
    throw new Error(`MAX_VUS must not exceed ${AUTH_MAX_VUS} when authRefresh is selected`)
  }

  return Object.freeze({
    targetEnv,
    baseUrl,
    allowedHosts: Object.freeze(allowedHosts),
    profile,
    fixturePath,
    runId: requireRunId(env.RUN_ID),
    prerequisiteSmokeRunId,
    smokeProofPath,
    commitSha: requireCommitSha(env.COMMIT_SHA),
    limits,
    scenarioNames,
  })
}
