import { check } from 'k6'

import { validateCapacityProof } from '../lib/capacity-proof.js'

export const options = { thresholds: { checks: ['rate==1'] } }

function throws(action) {
  try {
    action()
    return false
  } catch (_) {
    return true
  }
}

const EXPECTED = {
  previousStageNumber: 1,
  targetFingerprint: 'a'.repeat(64),
  fixtureSha256: 'b'.repeat(64),
  commitSha: '0123456789abcdef0123456789abcdef01234567',
  harnessCommitSha: 'fedcba9876543210fedcba9876543210fedcba98',
  scenarioNames: ['storeSearch', 'notificationHistory'],
}

function proof(overrides = {}) {
  return {
    schemaVersion: 'miriyum-k6-summary-v1',
    targetEnv: 'staging',
    profile: 'staging-capacity',
    thresholdsPassed: true,
    targetFingerprint: EXPECTED.targetFingerprint,
    fixtureSha256: EXPECTED.fixtureSha256,
    commitSha: EXPECTED.commitSha,
    harnessCommitSha: EXPECTED.harnessCommitSha,
    scenarioNames: [...EXPECTED.scenarioNames],
    capacity: { stageNumber: 1, targetRps: 10 },
    metrics: {
      storeSearch: { httpRequests: { count: 60, rate: 6 } },
      notificationHistory: { httpRequests: { count: 40, rate: 4 } },
    },
    ...overrides,
  }
}

export default function () {
  check(null, {
    'matching immediately previous capacity proof is accepted': () =>
      validateCapacityProof(proof(), EXPECTED).capacity.stageNumber === 1,
    'failed previous stage is rejected': () =>
      throws(() => validateCapacityProof(proof({ thresholdsPassed: false }), EXPECTED)),
    'non-adjacent previous stage is rejected': () =>
      throws(() => validateCapacityProof(proof({ capacity: { stageNumber: 0, targetRps: 1 } }), EXPECTED)),
    'different target is rejected': () =>
      throws(() => validateCapacityProof(proof({ targetFingerprint: 'c'.repeat(64) }), EXPECTED)),
    'different fixture is rejected': () =>
      throws(() => validateCapacityProof(proof({ fixtureSha256: 'd'.repeat(64) }), EXPECTED)),
    'different backend commit is rejected': () =>
      throws(() => validateCapacityProof(proof({ commitSha: '1'.repeat(40) }), EXPECTED)),
    'different harness commit is rejected': () =>
      throws(() => validateCapacityProof(proof({ harnessCommitSha: '2'.repeat(40) }), EXPECTED)),
    'capacity proof below its planned target RPS is rejected': () =>
      throws(() => validateCapacityProof(proof({
        metrics: {
          storeSearch: { httpRequests: { count: 50, rate: 5 } },
          notificationHistory: { httpRequests: { count: 39, rate: 3.9 } },
        },
      }), EXPECTED)),
    'different scenario composition is rejected': () =>
      throws(() => validateCapacityProof(proof({ scenarioNames: ['storeSearch'] }), EXPECTED)),
  })
}
