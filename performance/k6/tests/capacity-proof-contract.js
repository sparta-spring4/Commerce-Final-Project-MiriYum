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
    capacity: { stageNumber: 1, targetRps: 10 },
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
  })
}
