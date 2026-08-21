import { check } from 'k6'

import { loadSseConfig, requiredSmokeEndpointKinds } from '../sse/config.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

const SHA = '0123456789abcdef0123456789abcdef01234567'

function validEnv(overrides = {}) {
  return {
    TARGET_ENV: 'local',
    BASE_URL: 'https://loadtest-proxy:8443',
    ALLOWED_HOSTS: 'loadtest-proxy',
    SSE_PROFILE: 'smoke',
    SSE_FIXTURE_PATH: '/scripts/fixtures/sse-test-data.local.json',
    SSE_RUN_ID: 'local-sse-smoke-01',
    SSE_CONNECTIONS: '3',
    SSE_CONNECTIONS_PER_ACCOUNT: '2',
    SSE_HOLD_DURATION_SECONDS: '30',
    SSE_SLOW_CLIENT_DELAY_SECONDS: '2',
    SSE_SLOW_CLIENT_MAX_CLEANUP_SECONDS: '60',
    SSE_COMPANION_MIN_LIFETIME_SECONDS: '85',
    SSE_HTTP_PROBE_RATE: '3',
    SSE_HTTP_MAX_P95_RATIO: '2',
    SSE_SLOW_CLIENT_CONNECTIONS: '1',
    SSE_SLOW_CLIENT_TRIGGER_APPROVED: 'true',
    SSE_SLOW_CLIENT_IDEMPOTENCY_KEY: '123e4567-e89b-12d3-a456-426614174000',
    SSE_ENDPOINT_KINDS: 'notification-consumer,waiting-consumer,waiting-store-operator',
    COMMIT_SHA: SHA,
    HARNESS_COMMIT_SHA: SHA,
    ...overrides,
  }
}

function errorMessage(action) {
  try {
    action()
    return null
  } catch (error) {
    return error.message
  }
}

export default function () {
  check(null, {
    'recovery requires smoke proof for all SSE endpoint kinds': () =>
      requiredSmokeEndpointKinds('recovery', ['waiting-store-operator']).join(',')
        === 'notification-consumer,waiting-consumer,waiting-store-operator',
    'production host is rejected before SSE resolution': () => {
      const message = errorMessage(() => loadSseConfig(validEnv({
        TARGET_ENV: 'staging',
        BASE_URL: 'https://api.miriyum.com',
        ALLOWED_HOSTS: 'api.miriyum.com',
      })))
      return message === 'production target is forbidden'
    },
    'connection input above the test ceiling is rejected': () => {
      const message = errorMessage(() => loadSseConfig(validEnv({ SSE_CONNECTIONS: '201' })))
      return message !== null && message.includes('SSE_CONNECTIONS')
    },
    'per-account connection input above the backend ceiling is rejected': () => {
      const message = errorMessage(() => loadSseConfig(validEnv({
        SSE_CONNECTIONS_PER_ACCOUNT: '7',
      })))
      return message !== null && message.includes('SSE_CONNECTIONS_PER_ACCOUNT')
    },
    'non-smoke profiles require smoke proof': () => {
      const message = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'steady',
        SSE_SMOKE_PROOF_PATH: '',
      })))
      return message !== null && message.includes('SSE_SMOKE_PROOF_PATH')
    },
    'all SSE profiles are accepted with their required evidence': () => {
      const profiles = ['smoke', 'reconnect', 'steady', 'slow-client', 'capacity', 'recovery']
      return profiles.every((profile) => {
        const config = loadSseConfig(validEnv({
          SSE_PROFILE: profile,
          SSE_SMOKE_PROOF_PATH: profile === 'smoke' ? undefined : '/results/sse-smoke.json',
          ...(profile === 'slow-client' ? {
            SSE_CONNECTIONS: '2',
            SSE_HOLD_DURATION_SECONDS: '100',
            SSE_SLOW_CLIENT_DELAY_SECONDS: '40',
            SSE_ENDPOINT_KINDS: 'waiting-store-operator',
          } : {}),
          ...(profile === 'capacity' ? {
            SSE_CONNECTIONS: '7',
            SSE_CONNECTIONS_PER_ACCOUNT: '7',
            SSE_ENDPOINT_KINDS: 'notification-consumer',
          } : {}),
          ...(profile === 'recovery' ? {
            SSE_CONNECTIONS: '1',
            SSE_ENDPOINT_KINDS: 'waiting-store-operator',
            SSE_RECOVERY_ARM_DELAY_SECONDS: '15',
            SSE_RECOVERY_MAX_SECONDS: '6',
            SSE_RECOVERY_TRIGGER_APPROVED: 'true',
            SSE_RECOVERY_TRIGGER_IDEMPOTENCY_KEY: '123e4567-e89b-12d3-a456-426614174000',
            SSE_RECOVERY_CLEANUP_IDEMPOTENCY_KEY: '223e4567-e89b-12d3-a456-426614174000',
          } : {}),
        }))
        return config.profile === profile
      })
    },
    'recovery profile is single-scope bounded and explicitly approved': () => {
      const config = loadSseConfig(validEnv({
        SSE_PROFILE: 'recovery',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '1',
        SSE_ENDPOINT_KINDS: 'waiting-store-operator',
        SSE_RECOVERY_ARM_DELAY_SECONDS: '15',
        SSE_RECOVERY_MAX_SECONDS: '6',
        SSE_RECOVERY_TRIGGER_APPROVED: 'true',
        SSE_RECOVERY_TRIGGER_IDEMPOTENCY_KEY: '123e4567-e89b-12d3-a456-426614174000',
        SSE_RECOVERY_CLEANUP_IDEMPOTENCY_KEY: '223e4567-e89b-12d3-a456-426614174000',
      }))
      return config.profile === 'recovery'
        && config.recoveryArmDelaySeconds === 15
        && config.recoveryMaxSeconds === 6
        && config.recoveryTriggerIdempotencyKey !== config.recoveryCleanupIdempotencyKey
    },
    'capacity profile requires a single-account overflow shape': () => {
      const wrongKinds = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'capacity',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '7',
        SSE_CONNECTIONS_PER_ACCOUNT: '7',
      })))
      const belowCeiling = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'capacity',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '6',
        SSE_CONNECTIONS_PER_ACCOUNT: '6',
        SSE_ENDPOINT_KINDS: 'notification-consumer',
      })))
      return wrongKinds?.includes('capacity') === true
        && belowCeiling?.includes('capacity') === true
    },
    'non-smoke profiles require bounded owned HTTP probe inputs': () => {
      const missingRate = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'steady',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_HTTP_PROBE_RATE: '',
      })))
      const invalidRatio = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'reconnect',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_HTTP_MAX_P95_RATIO: '11',
      })))
      return missingRate?.includes('SSE_HTTP_PROBE_RATE') === true
        && invalidRatio?.includes('SSE_HTTP_MAX_P95_RATIO') === true
    },
    'slow-client requires one operator scope and a normal companion': () => {
      const mixedKinds = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'slow-client',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '2',
      })))
      const noCompanion = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'slow-client',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '1',
        SSE_ENDPOINT_KINDS: 'waiting-store-operator',
      })))
      const multipleCompanions = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'slow-client',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '3',
        SSE_SLOW_CLIENT_CONNECTIONS: '1',
        SSE_HOLD_DURATION_SECONDS: '100',
        SSE_SLOW_CLIENT_DELAY_SECONDS: '40',
        SSE_ENDPOINT_KINDS: 'waiting-store-operator',
      })))
      return mixedKinds === 'slow-client requires only waiting-store-operator'
        && noCompanion === 'slow-client requires slow and companion connections'
        && multipleCompanions === 'slow-client requires exactly one companion connection'
    },
    'slow-client mutation is explicit and fail-closed': () => {
      const unapproved = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'slow-client',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '2',
        SSE_ENDPOINT_KINDS: 'waiting-store-operator',
        SSE_SLOW_CLIENT_TRIGGER_APPROVED: 'false',
      })))
      const invalidKey = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'slow-client',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '2',
        SSE_ENDPOINT_KINDS: 'waiting-store-operator',
        SSE_SLOW_CLIENT_IDEMPOTENCY_KEY: 'not-a-uuid',
      })))
      return unapproved?.includes('SSE_SLOW_CLIENT_TRIGGER_APPROVED') === true
        && invalidKey?.includes('SSE_SLOW_CLIENT_IDEMPOTENCY_KEY') === true
    },
    'slow-client backpressure windows are explicit and strictly ordered': () => {
      let config = null
      const validError = errorMessage(() => {
        config = loadSseConfig(validEnv({
          SSE_PROFILE: 'slow-client',
          SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
          SSE_CONNECTIONS: '2',
          SSE_HOLD_DURATION_SECONDS: '100',
          SSE_SLOW_CLIENT_DELAY_SECONDS: '40',
          SSE_SLOW_CLIENT_MAX_CLEANUP_SECONDS: '60',
          SSE_COMPANION_MIN_LIFETIME_SECONDS: '85',
          SSE_ENDPOINT_KINDS: 'waiting-store-operator',
        }))
      })
      const unordered = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'slow-client',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '2',
        SSE_HOLD_DURATION_SECONDS: '100',
        SSE_SLOW_CLIENT_DELAY_SECONDS: '40',
        SSE_SLOW_CLIENT_MAX_CLEANUP_SECONDS: '90',
        SSE_COMPANION_MIN_LIFETIME_SECONDS: '85',
        SSE_ENDPOINT_KINDS: 'waiting-store-operator',
      })))
      const tooShort = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'slow-client',
        SSE_SMOKE_PROOF_PATH: '/results/sse-smoke.json',
        SSE_CONNECTIONS: '2',
        SSE_HOLD_DURATION_SECONDS: '100',
        SSE_SLOW_CLIENT_DELAY_SECONDS: '30',
        SSE_SLOW_CLIENT_MAX_CLEANUP_SECONDS: '60',
        SSE_COMPANION_MIN_LIFETIME_SECONDS: '85',
        SSE_ENDPOINT_KINDS: 'waiting-store-operator',
      })))
      return validError === null
        && config.slowClientDelaySeconds === 40
        && config.slowClientMaxCleanupSeconds === 60
        && config.companionMinLifetimeSeconds === 85
        && tooShort === 'slow-client timing windows must be strictly ordered'
        && unordered === 'slow-client timing windows must be strictly ordered'
    },
    'unknown SSE profile is rejected': () =>
      errorMessage(() => loadSseConfig(validEnv({ SSE_PROFILE: 'burst' })))
        ?.includes('SSE_PROFILE') === true,
    'local execution requires matching full SHAs': () =>
      errorMessage(() => loadSseConfig(validEnv({
        HARNESS_COMMIT_SHA: 'fedcba9876543210fedcba9876543210fedcba98',
      })))?.includes('matching') === true,
    'abbreviated SHA is rejected': () =>
      errorMessage(() => loadSseConfig(validEnv({ COMMIT_SHA: '0123456' })))
        ?.includes('40-character') === true,
    'staging requires approval and verified harness source': () =>
      errorMessage(() => loadSseConfig(validEnv({
        TARGET_ENV: 'staging',
        BASE_URL: 'https://staging-api.miriyum.click',
        ALLOWED_HOSTS: 'staging-api.miriyum.click',
      })))?.includes('STAGING_APPROVED') === true,
    'approved staging target preserves reviewed hostname and evidence': () => {
      const config = loadSseConfig(validEnv({
        TARGET_ENV: 'staging',
        BASE_URL: 'https://staging-api.miriyum.click/',
        ALLOWED_HOSTS: 'staging-api.miriyum.click',
        STAGING_APPROVED: 'true',
        STAGING_HARNESS_SOURCE_VERIFIED: 'true',
      }))
      return config.targetEnv === 'staging'
        && config.baseUrl === 'https://staging-api.miriyum.click'
    },
    'unsafe run ID is rejected': () =>
      errorMessage(() => loadSseConfig(validEnv({ SSE_RUN_ID: 'unsafe run/id' })))
        ?.includes('SSE_RUN_ID') === true,
    'fixture and smoke proof paths must reference JSON': () => {
      const fixtureError = errorMessage(() => loadSseConfig(validEnv({
        SSE_FIXTURE_PATH: '/scripts/fixtures/accounts.txt',
      })))
      const proofError = errorMessage(() => loadSseConfig(validEnv({
        SSE_PROFILE: 'reconnect',
        SSE_SMOKE_PROOF_PATH: '/results/smoke.md',
      })))
      return fixtureError?.includes('SSE_FIXTURE_PATH') === true
        && proofError?.includes('SSE_SMOKE_PROOF_PATH') === true
    },
    'hold and slow-client duration inputs are bounded': () => {
      const hold = errorMessage(() => loadSseConfig(validEnv({
        SSE_HOLD_DURATION_SECONDS: '601',
      })))
      const slow = errorMessage(() => loadSseConfig(validEnv({
        SSE_SLOW_CLIENT_DELAY_SECONDS: '61',
      })))
      return hold?.includes('SSE_HOLD_DURATION_SECONDS') === true
        && slow?.includes('SSE_SLOW_CLIENT_DELAY_SECONDS') === true
    },
    'endpoint kinds must be known and unique': () => {
      const unknown = errorMessage(() => loadSseConfig(validEnv({
        SSE_ENDPOINT_KINDS: 'notification-consumer,unknown',
      })))
      const duplicate = errorMessage(() => loadSseConfig(validEnv({
        SSE_ENDPOINT_KINDS: 'waiting-consumer,waiting-consumer',
      })))
      return unknown?.includes('SSE_ENDPOINT_KINDS') === true
        && duplicate?.includes('SSE_ENDPOINT_KINDS') === true
    },
    'valid config is frozen and never preserves credential inputs': () => {
      const config = loadSseConfig(validEnv({
        K6_CONSUMER_01_EMAIL: 'synthetic@example.test',
        K6_CONSUMER_01_PASSWORD: 'not-for-config-output',
      }))
      const serialized = JSON.stringify(config)
      return Object.isFrozen(config)
        && Object.isFrozen(config.endpointKinds)
        && !serialized.includes('synthetic@example.test')
        && !serialized.includes('not-for-config-output')
    },
  })
}
