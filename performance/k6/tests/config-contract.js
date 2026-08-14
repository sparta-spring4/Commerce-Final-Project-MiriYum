import { check } from 'k6'

import { loadConfig } from '../config.js'
import { assertSafeTarget, parsePositiveInt } from '../lib/safety.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

function throws(action) {
  try {
    action()
    return false
  } catch (_) {
    return true
  }
}

const LOCAL_SMOKE_ENV = {
  TARGET_ENV: 'local',
  BASE_URL: 'http://backend:8080',
  ALLOWED_HOSTS: 'backend',
  PROFILE: 'smoke',
  FIXTURE_PATH: '/scripts/fixtures/test-data.local.json',
  RUN_ID: 'local-smoke-20260814',
}

export default function () {
  check(null, {
    'production host is rejected before requests': () =>
      throws(() => assertSafeTarget('staging', 'https://api.miriyum.com', ['api.miriyum.com'])),
    'unknown target environment is rejected': () =>
      throws(() => assertSafeTarget('qa', 'https://qa.miriyum.test', ['qa.miriyum.test'])),
    'host outside the explicit allowlist is rejected': () =>
      throws(() => assertSafeTarget('staging', 'https://other.example', ['staging.miriyum.test'])),
    'non-TLS staging target is rejected': () =>
      throws(() => assertSafeTarget('staging', 'http://staging.miriyum.test', ['staging.miriyum.test'])),
    'zero load input is rejected': () =>
      throws(() => parsePositiveInt('MAX_VUS', '0', 100)),
    'load above its hard ceiling is rejected': () =>
      throws(() => parsePositiveInt('MAX_VUS', '101', 100)),
    'local smoke has fixed minimal limits': () => {
      const smoke = loadConfig(LOCAL_SMOKE_ENV)
      return smoke.profile === 'smoke'
        && smoke.targetEnv === 'local'
        && smoke.limits.maxVus === 1
    },
    'local baseline requires explicit load inputs': () =>
      throws(() => loadConfig({ ...LOCAL_SMOKE_ENV, PROFILE: 'local-baseline' })),
    'staging requires an explicit approval gate': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        TARGET_ENV: 'staging',
        BASE_URL: 'https://staging.miriyum.test',
        ALLOWED_HOSTS: 'staging.miriyum.test',
      })),
    'staging baseline requires prior smoke evidence': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        TARGET_ENV: 'staging',
        BASE_URL: 'https://staging.miriyum.test',
        ALLOWED_HOSTS: 'staging.miriyum.test',
        PROFILE: 'staging-baseline',
        STAGING_APPROVED: 'true',
        MAX_VUS: '2',
        DURATION_SECONDS: '30',
        ARRIVAL_RATE: '2',
      })),
  })
}
