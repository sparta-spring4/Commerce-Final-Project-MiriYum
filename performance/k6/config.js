import { assertSafeTarget, parsePositiveInt } from './lib/safety.js'

const VALID_PROFILES = new Set(['smoke', 'local-baseline', 'staging-baseline', 'staging-capacity'])
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

function requireSecret(name, rawValue) {
  if (typeof rawValue !== 'string' || rawValue === '') {
    throw new Error(`${name} is required`)
  }
  return rawValue
}

function requireRunId(rawValue) {
  const runId = requireText('RUN_ID', rawValue)
  if (runId.length > 100 || !/^[A-Za-z0-9._-]+$/.test(runId)) {
    throw new Error('RUN_ID must contain only safe identifier characters')
  }
  return runId
}

function requireCommitSha(name, rawValue) {
  const commitSha = requireText(name, rawValue).toLowerCase()
  if (!/^[0-9a-f]{40}$/.test(commitSha)) {
    throw new Error(`${name} must be a full 40-character Git SHA`)
  }
  return commitSha
}

export function validateShaEvidence({
  targetEnv,
  commitSha,
  harnessCommitSha,
  stagingSplitApproved,
  harnessSourceVerified,
}) {
  const normalizedCommitSha = requireCommitSha('COMMIT_SHA', commitSha)
  const normalizedHarnessCommitSha = requireCommitSha('HARNESS_COMMIT_SHA', harnessCommitSha)
  if (targetEnv === 'local' && normalizedCommitSha !== normalizedHarnessCommitSha) {
    throw new Error('local execution requires matching deployed and harness SHAs')
  }
  if (targetEnv === 'staging' && harnessSourceVerified !== true) {
    throw new Error('staging execution requires STAGING_HARNESS_SOURCE_VERIFIED=true')
  }
  if (targetEnv === 'staging'
    && normalizedCommitSha !== normalizedHarnessCommitSha
    && stagingSplitApproved !== true) {
    throw new Error('staging split SHA requires STAGING_SPLIT_SHA_APPROVED=true')
  }
  return Object.freeze({
    commitSha: normalizedCommitSha,
    harnessCommitSha: normalizedHarnessCommitSha,
  })
}

function requireJsonPath(name, rawValue) {
  const path = requireText(name, rawValue)
  if (!path.endsWith('.json')) {
    throw new Error(`${name} must reference a JSON file`)
  }
  return path
}

function requirePositiveInteger(name, rawValue) {
  const value = Number(requireText(name, rawValue))
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer`)
  }
  return value
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
    throw new Error('PROFILE must be smoke, local-baseline, staging-baseline, or staging-capacity')
  }

  if (targetEnv === 'staging' && env.STAGING_APPROVED !== 'true') {
    throw new Error('staging execution requires STAGING_APPROVED=true')
  }
  if (profile === 'local-baseline' && targetEnv !== 'local') {
    throw new Error('local-baseline requires TARGET_ENV=local')
  }
  if (profile === 'staging-baseline' && targetEnv !== 'staging') {
    throw new Error('staging-baseline requires TARGET_ENV=staging')
  }
  if (profile === 'staging-capacity' && targetEnv !== 'staging') {
    throw new Error('staging-capacity requires TARGET_ENV=staging')
  }
  if (profile === 'staging-capacity' && env.CAPACITY_TEST_APPROVED !== 'true') {
    throw new Error('staging-capacity requires CAPACITY_TEST_APPROVED=true')
  }
  const prerequisiteSmokeRunId = profile === 'local-baseline'
    ? requireRunId(env.LOCAL_SMOKE_RUN_ID)
    : profile === 'staging-baseline' || profile === 'staging-capacity'
      ? requireRunId(env.STAGING_SMOKE_RUN_ID)
      : null
  const smokeProofPath = profile === 'smoke'
    ? null
    : requireJsonPath('SMOKE_PROOF_PATH', env.SMOKE_PROOF_PATH)

  const fixturePath = requireJsonPath('FIXTURE_PATH', env.FIXTURE_PATH)
  const shaEvidence = validateShaEvidence({
    targetEnv,
    commitSha: env.COMMIT_SHA,
    harnessCommitSha: env.HARNESS_COMMIT_SHA,
    stagingSplitApproved: env.STAGING_SPLIT_SHA_APPROVED === 'true',
    harnessSourceVerified: env.STAGING_HARNESS_SOURCE_VERIFIED === 'true',
  })

  const limits = loadLimits(profile, env)
  const scenarioNames = loadScenarioNames(env.SCENARIOS)
  const capacityStageNumber = profile === 'staging-capacity'
    ? requirePositiveInteger('CAPACITY_STAGE_NUMBER', env.CAPACITY_STAGE_NUMBER)
    : null
  const capacityTargetRps = profile === 'staging-capacity'
    ? requirePositiveInteger('CAPACITY_TARGET_RPS', env.CAPACITY_TARGET_RPS)
    : null
  const capacityPreviousProofPath = capacityStageNumber !== null && capacityStageNumber > 1
    ? requireJsonPath('CAPACITY_PREVIOUS_PROOF_PATH', env.CAPACITY_PREVIOUS_PROOF_PATH)
    : null
  if (profile === 'staging-capacity'
    && scenarioNames.includes('storeSearch')
    && env.CAPACITY_LLM_DISABLED_CONFIRMED !== 'true') {
    throw new Error('storeSearch capacity requires CAPACITY_LLM_DISABLED_CONFIRMED=true')
  }
  if (targetEnv === 'staging'
    && scenarioNames.includes('reservationCreate')
    && env.STAGING_RESERVATION_FIXTURE_APPROVED !== 'true') {
    throw new Error('staging reservationCreate requires STAGING_RESERVATION_FIXTURE_APPROVED=true')
  }
  assertSafeTarget(targetEnv, baseUrl, allowedHosts)
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
    ...shaEvidence,
    limits,
    scenarioNames,
    capacityStageNumber,
    capacityTargetRps,
    capacityPreviousProofPath,
  })
}

export function loadRecoveryConfig(env) {
  const targetEnv = requireText('TARGET_ENV', env.TARGET_ENV)
  if (targetEnv !== 'staging') {
    throw new Error('rate-limit recovery verification requires TARGET_ENV=staging')
  }
  if (env.STAGING_APPROVED !== 'true') {
    throw new Error('rate-limit recovery verification requires STAGING_APPROVED=true')
  }
  if (env.RECOVERY_VERIFICATION_APPROVED !== 'true') {
    throw new Error('rate-limit recovery verification requires RECOVERY_VERIFICATION_APPROVED=true')
  }
  if (env.RATE_LIMIT_EXCEPTION_REMOVED !== 'true') {
    throw new Error('rate-limit recovery verification requires RATE_LIMIT_EXCEPTION_REMOVED=true')
  }
  if (env.RATE_LIMIT_WINDOW_CONFIRMED !== 'true') {
    throw new Error('rate-limit recovery verification requires a fresh confirmed rate-limit window')
  }

  const baseUrl = requireText('BASE_URL', env.BASE_URL).replace(/\/+$/, '')
  const allowedHosts = parseAllowedHosts(env.ALLOWED_HOSTS)
  const shaEvidence = validateShaEvidence({
    targetEnv,
    commitSha: env.COMMIT_SHA,
    harnessCommitSha: env.HARNESS_COMMIT_SHA,
    stagingSplitApproved: env.STAGING_SPLIT_SHA_APPROVED === 'true',
    harnessSourceVerified: env.STAGING_HARNESS_SOURCE_VERIFIED === 'true',
  })
  const account = Object.freeze({
    email: requireText('K6_RECOVERY_EMAIL', env.K6_RECOVERY_EMAIL),
    password: requireSecret('K6_RECOVERY_PASSWORD', env.K6_RECOVERY_PASSWORD),
  })
  assertSafeTarget(targetEnv, baseUrl, allowedHosts)

  return Object.freeze({
    targetEnv,
    baseUrl,
    allowedHosts: Object.freeze(allowedHosts),
    runId: requireRunId(env.RUN_ID),
    ...shaEvidence,
    account,
  })
}
