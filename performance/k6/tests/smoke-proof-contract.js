import { check } from 'k6'

import {
  createFixtureFingerprint,
  createTargetFingerprint,
  validateSmokeProof,
} from '../lib/smoke-proof.js'

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

const EXPECTED = {
  targetEnv: 'local',
  baseUrl: 'https://loadtest-proxy:8443',
  commitSha: '0123456789abcdef0123456789abcdef01234567',
  fixtureText: '{"fixture":"synthetic"}',
  smokeRunId: 'local-smoke-approved',
  scenarioNames: ['storeSearch', 'reservationCreate'],
}

function proof(overrides = {}) {
  return {
    schemaVersion: 'miriyum-k6-summary-v1',
    targetEnv: EXPECTED.targetEnv,
    profile: 'smoke',
    runId: EXPECTED.smokeRunId,
    prerequisiteSmokeRunId: null,
    commitSha: EXPECTED.commitSha,
    scenarioNames: ['authRefresh', 'storeSearch', 'reservationCreate', 'notificationHistory'],
    targetFingerprint: createTargetFingerprint(EXPECTED.targetEnv, EXPECTED.baseUrl),
    fixtureSha256: createFixtureFingerprint(EXPECTED.fixtureText),
    thresholdsPassed: true,
    limits: { maxVus: 1, durationSeconds: 1, arrivalRate: 1 },
    metrics: {},
    ...overrides,
  }
}

export default function () {
  check(null, {
    'matching successful smoke artifact is accepted': () =>
      validateSmokeProof(proof(), EXPECTED).runId === EXPECTED.smokeRunId,
    'arbitrary smoke run id without matching artifact is rejected': () =>
      throws(() => validateSmokeProof(proof({ runId: 'anything' }), EXPECTED)),
    'failed smoke thresholds are rejected': () =>
      throws(() => validateSmokeProof(proof({ thresholdsPassed: false }), EXPECTED)),
    'different target is rejected': () =>
      throws(() => validateSmokeProof(proof({ targetFingerprint: 'a'.repeat(64) }), EXPECTED)),
    'different fixture is rejected': () =>
      throws(() => validateSmokeProof(proof({ fixtureSha256: 'b'.repeat(64) }), EXPECTED)),
    'different commit is rejected': () =>
      throws(() => validateSmokeProof(proof({ commitSha: 'f'.repeat(40) }), EXPECTED)),
    'smoke must cover every baseline scenario': () =>
      throws(() => validateSmokeProof(proof({ scenarioNames: ['storeSearch'] }), EXPECTED)),
    'non-smoke artifact is rejected': () =>
      throws(() => validateSmokeProof(proof({ profile: 'local-baseline' }), EXPECTED)),
  })
}
