import { check } from 'k6'

import { loadConfig, loadRecoveryConfig, validateShaEvidence } from '../config.js'
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

function errorMessage(action) {
  try {
    action()
    return null
  } catch (error) {
    return error.message
  }
}

const LOCAL_SMOKE_ENV = {
  TARGET_ENV: 'local',
  BASE_URL: 'https://loadtest-proxy:8443',
  ALLOWED_HOSTS: 'loadtest-proxy',
  PROFILE: 'smoke',
  FIXTURE_PATH: '/scripts/fixtures/test-data.local.json',
  RUN_ID: 'local-smoke-20260814',
  COMMIT_SHA: '0123456789abcdef0123456789abcdef01234567',
  HARNESS_COMMIT_SHA: '0123456789abcdef0123456789abcdef01234567',
}

const RECOVERY_ENV = {
  ...LOCAL_SMOKE_ENV,
  TARGET_ENV: 'staging',
  BASE_URL: 'https://staging.example.test',
  ALLOWED_HOSTS: 'staging.example.test',
  STAGING_APPROVED: 'true',
  STAGING_HARNESS_SOURCE_VERIFIED: 'true',
  RECOVERY_VERIFICATION_APPROVED: 'true',
  RATE_LIMIT_EXCEPTION_REMOVED: 'true',
  RATE_LIMIT_WINDOW_CONFIRMED: 'true',
  K6_RECOVERY_EMAIL: 'synthetic@example.test',
  K6_RECOVERY_PASSWORD: ' synthetic password ',
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
    'non-TLS local target is rejected to preserve secure refresh cookies': () =>
      throws(() => assertSafeTarget('local', 'http://loadtest-proxy:8443', ['loadtest-proxy'])),
    'remote host cannot be disguised as a local target': () =>
      throws(() => assertSafeTarget('local', 'https://api.example.test', ['api.example.test'])),
    'unreviewed external host cannot be selected as staging': () =>
      throws(() => assertSafeTarget('staging', 'https://staging.example.test', ['staging.example.test'])),
    'reviewed staging host requires HTTPS and an explicit caller allowlist': () =>
      !throws(() => assertSafeTarget(
        'staging',
        'https://staging-api.miriyum.click',
        ['staging-api.miriyum.click'],
      )),
    'zero load input is rejected': () =>
      throws(() => parsePositiveInt('MAX_VUS', '0', 100)),
    'load above its hard ceiling is rejected': () =>
      throws(() => parsePositiveInt('MAX_VUS', '101', 100)),
    'local smoke has fixed minimal limits': () => {
      const smoke = loadConfig(LOCAL_SMOKE_ENV)
      return smoke.profile === 'smoke'
        && smoke.targetEnv === 'local'
        && smoke.limits.maxVus === 1
        && smoke.scenarioNames.length === 4
    },
    'local baseline requires explicit load inputs': () =>
      throws(() => loadConfig({ ...LOCAL_SMOKE_ENV, PROFILE: 'local-baseline' })),
    'local baseline requires prior local smoke evidence': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        PROFILE: 'local-baseline',
        SCENARIOS: 'storeSearch',
        MAX_VUS: '1',
        DURATION_SECONDS: '10',
        ARRIVAL_RATE: '1',
      })),
    'local baseline rejects a smoke run ID without its artifact': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        PROFILE: 'local-baseline',
        SCENARIOS: 'storeSearch',
        MAX_VUS: '1',
        DURATION_SECONDS: '10',
        ARRIVAL_RATE: '1',
        LOCAL_SMOKE_RUN_ID: 'local-smoke-approved',
      })),
    'local baseline requires a JSON smoke proof artifact path': () => {
      const baseline = loadConfig({
        ...LOCAL_SMOKE_ENV,
        PROFILE: 'local-baseline',
        SCENARIOS: 'storeSearch',
        MAX_VUS: '1',
        DURATION_SECONDS: '10',
        ARRIVAL_RATE: '1',
        LOCAL_SMOKE_RUN_ID: 'local-smoke-approved',
        SMOKE_PROOF_PATH: '/results/local-smoke-approved.json',
      })
      return baseline.prerequisiteSmokeRunId === 'local-smoke-approved'
        && baseline.smokeProofPath === '/results/local-smoke-approved.json'
    },
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
    'missing commit SHA is rejected': () => {
      const withoutCommit = { ...LOCAL_SMOKE_ENV }
      delete withoutCommit.COMMIT_SHA
      return throws(() => loadConfig(withoutCommit))
    },
    'missing harness commit SHA is rejected': () => {
      const withoutHarnessCommit = { ...LOCAL_SMOKE_ENV }
      delete withoutHarnessCommit.HARNESS_COMMIT_SHA
      return throws(() => loadConfig(withoutHarnessCommit))
    },
    'local execution rejects split deployed and harness SHAs': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        HARNESS_COMMIT_SHA: 'fedcba9876543210fedcba9876543210fedcba98',
      })),
    'staging split SHA requires a dedicated approval': () =>
      throws(() => validateShaEvidence({
        targetEnv: 'staging',
        commitSha: LOCAL_SMOKE_ENV.COMMIT_SHA,
        harnessCommitSha: 'fedcba9876543210fedcba9876543210fedcba98',
        stagingSplitApproved: false,
        harnessSourceVerified: true,
      })),
    'staging requires verified harness source provenance': () =>
      throws(() => validateShaEvidence({
        targetEnv: 'staging',
        commitSha: LOCAL_SMOKE_ENV.COMMIT_SHA,
        harnessCommitSha: LOCAL_SMOKE_ENV.HARNESS_COMMIT_SHA,
        stagingSplitApproved: false,
        harnessSourceVerified: false,
      })),
    'approved staging split SHA preserves both full SHAs': () => {
      const evidence = validateShaEvidence({
        targetEnv: 'staging',
        commitSha: LOCAL_SMOKE_ENV.COMMIT_SHA,
        harnessCommitSha: 'fedcba9876543210fedcba9876543210fedcba98',
        stagingSplitApproved: true,
        harnessSourceVerified: true,
      })
      return evidence.commitSha === LOCAL_SMOKE_ENV.COMMIT_SHA
        && evidence.harnessCommitSha === 'fedcba9876543210fedcba9876543210fedcba98'
    },
    'recovery verification is staging-only': () =>
      errorMessage(() => loadRecoveryConfig({
        ...RECOVERY_ENV,
        TARGET_ENV: 'local',
        BASE_URL: 'https://loadtest-proxy:8443',
        ALLOWED_HOSTS: 'loadtest-proxy',
      })) === 'rate-limit recovery verification requires TARGET_ENV=staging',
    'recovery verification requires its dedicated approval': () => {
      const env = { ...RECOVERY_ENV }
      delete env.RECOVERY_VERIFICATION_APPROVED
      return errorMessage(() => loadRecoveryConfig(env))
        === 'rate-limit recovery verification requires RECOVERY_VERIFICATION_APPROVED=true'
    },
    'recovery verification requires the exception removal attestation': () => {
      const env = { ...RECOVERY_ENV }
      delete env.RATE_LIMIT_EXCEPTION_REMOVED
      return errorMessage(() => loadRecoveryConfig(env))
        === 'rate-limit recovery verification requires RATE_LIMIT_EXCEPTION_REMOVED=true'
    },
    'recovery verification requires a fresh rate-limit window': () => {
      const env = { ...RECOVERY_ENV }
      delete env.RATE_LIMIT_WINDOW_CONFIRMED
      return errorMessage(() => loadRecoveryConfig(env))
        === 'rate-limit recovery verification requires a fresh confirmed rate-limit window'
    },
    'recovery verification remains closed until a reviewed staging host exists': () =>
      errorMessage(() => loadRecoveryConfig(RECOVERY_ENV))
        === 'staging target host is not configured in the trusted repository allowlist',
    'staging reservation remains blocked until the #358 fixture contract is approved': () =>
      errorMessage(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        TARGET_ENV: 'staging',
        BASE_URL: 'https://staging.example.test',
        ALLOWED_HOSTS: 'staging.example.test',
        STAGING_APPROVED: 'true',
        STAGING_HARNESS_SOURCE_VERIFIED: 'true',
      })) === 'staging reservationCreate requires STAGING_RESERVATION_FIXTURE_APPROVED=true',
    'approved non-reservation staging selection proceeds to the trusted-host gate': () =>
      errorMessage(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        TARGET_ENV: 'staging',
        BASE_URL: 'https://staging.example.test',
        ALLOWED_HOSTS: 'staging.example.test',
        STAGING_APPROVED: 'true',
        STAGING_HARNESS_SOURCE_VERIFIED: 'true',
        SCENARIOS: 'authRefresh,storeSearch,notificationHistory',
      })) === 'staging target host is not configured in the trusted repository allowlist',
    'approved staging reservation proceeds to the trusted-host gate': () =>
      errorMessage(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        TARGET_ENV: 'staging',
        BASE_URL: 'https://staging.example.test',
        ALLOWED_HOSTS: 'staging.example.test',
        STAGING_APPROVED: 'true',
        STAGING_HARNESS_SOURCE_VERIFIED: 'true',
        STAGING_RESERVATION_FIXTURE_APPROVED: 'true',
        SCENARIOS: 'reservationCreate',
      })) === 'staging target host is not configured in the trusted repository allowlist',
    'duplicate scenario selection is rejected': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        SCENARIOS: 'storeSearch,storeSearch',
      })),
    'baseline VU ceiling covers every selected scenario': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        PROFILE: 'local-baseline',
        SCENARIOS: 'authRefresh,storeSearch',
        MAX_VUS: '1',
        DURATION_SECONDS: '10',
        ARRIVAL_RATE: '2',
        LOCAL_SMOKE_RUN_ID: 'local-smoke-approved',
      })),
    'baseline arrival rate covers every selected scenario': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        PROFILE: 'local-baseline',
        SCENARIOS: 'authRefresh,storeSearch',
        MAX_VUS: '2',
        DURATION_SECONDS: '10',
        ARRIVAL_RATE: '1',
        LOCAL_SMOKE_RUN_ID: 'local-smoke-approved',
      })),
    'auth baseline VU ceiling stays below the CSRF preparation budget': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        PROFILE: 'local-baseline',
        SCENARIOS: 'authRefresh',
        MAX_VUS: '51',
        DURATION_SECONDS: '10',
        ARRIVAL_RATE: '1',
        LOCAL_SMOKE_RUN_ID: 'local-smoke-approved',
        SMOKE_PROOF_PATH: '/results/local-smoke-approved.json',
      })),
    'non-auth baseline can use the general VU ceiling': () => {
      const baseline = loadConfig({
        ...LOCAL_SMOKE_ENV,
        PROFILE: 'local-baseline',
        SCENARIOS: 'reservationCreate',
        MAX_VUS: '100',
        DURATION_SECONDS: '10',
        ARRIVAL_RATE: '1',
        LOCAL_SMOKE_RUN_ID: 'local-smoke-approved',
        SMOKE_PROOF_PATH: '/results/local-smoke-approved.json',
      })
      return baseline.limits.maxVus === 100
    },
    'baseline longer than the bearer validity safety window is rejected': () =>
      throws(() => loadConfig({
        ...LOCAL_SMOKE_ENV,
        PROFILE: 'local-baseline',
        SCENARIOS: 'storeSearch',
        MAX_VUS: '1',
        DURATION_SECONDS: '601',
        ARRIVAL_RATE: '1',
        LOCAL_SMOKE_RUN_ID: 'local-smoke-approved',
      })),
  })
}
