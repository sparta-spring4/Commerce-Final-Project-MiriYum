import { check } from 'k6'

import { loadSseConfig } from '../sse/config.js'

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
    'production host is rejected before SSE resolution': () => {
      const message = errorMessage(() => loadSseConfig(validEnv({
        TARGET_ENV: 'staging',
        BASE_URL: 'https://production.miriyum.click',
        ALLOWED_HOSTS: 'production.miriyum.click',
      })))
      return message !== null && message.includes('target')
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
    'all four SSE profiles are accepted with their required evidence': () => {
      const profiles = ['smoke', 'reconnect', 'steady', 'slow-client']
      return profiles.every((profile) => {
        const config = loadSseConfig(validEnv({
          SSE_PROFILE: profile,
          SSE_SMOKE_PROOF_PATH: profile === 'smoke' ? undefined : '/results/sse-smoke.json',
        }))
        return config.profile === profile
      })
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
        SSE_SLOW_CLIENT_DELAY_SECONDS: '31',
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
