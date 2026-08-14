import { assertSafeTarget, parsePositiveInt } from './lib/safety.js'

const VALID_PROFILES = new Set(['smoke', 'local-baseline', 'staging-baseline'])
const HARD_LIMITS = Object.freeze({
  maxVus: 100,
  durationSeconds: 3600,
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

export function loadConfig(env) {
  const targetEnv = requireText('TARGET_ENV', env.TARGET_ENV)
  const baseUrl = requireText('BASE_URL', env.BASE_URL).replace(/\/+$/, '')
  const allowedHosts = parseAllowedHosts(env.ALLOWED_HOSTS)
  const profile = requireText('PROFILE', env.PROFILE)
  if (!VALID_PROFILES.has(profile)) {
    throw new Error('PROFILE must be smoke, local-baseline, or staging-baseline')
  }

  assertSafeTarget(targetEnv, baseUrl, allowedHosts)
  if (profile === 'local-baseline' && targetEnv !== 'local') {
    throw new Error('local-baseline requires TARGET_ENV=local')
  }
  if (profile === 'staging-baseline' && targetEnv !== 'staging') {
    throw new Error('staging-baseline requires TARGET_ENV=staging')
  }
  if (targetEnv === 'staging' && env.STAGING_APPROVED !== 'true') {
    throw new Error('staging execution requires STAGING_APPROVED=true')
  }
  if (profile === 'staging-baseline') {
    requireRunId(env.STAGING_SMOKE_RUN_ID)
  }

  const fixturePath = requireText('FIXTURE_PATH', env.FIXTURE_PATH)
  if (!fixturePath.endsWith('.json')) {
    throw new Error('FIXTURE_PATH must reference a JSON file')
  }

  return Object.freeze({
    targetEnv,
    baseUrl,
    allowedHosts: Object.freeze(allowedHosts),
    profile,
    fixturePath,
    runId: requireRunId(env.RUN_ID),
    limits: loadLimits(profile, env),
  })
}
