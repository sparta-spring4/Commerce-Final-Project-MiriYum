import { sha256 } from 'k6/crypto'

const SUMMARY_SCHEMA_VERSION = 'miriyum-k6-summary-v1'
const SHA256_PATTERN = /^[0-9a-f]{64}$/

function requireText(value, name) {
  if (typeof value !== 'string' || value === '') {
    throw new Error(`${name} must be a non-empty string`)
  }
  return value
}

function requireObject(value, name) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`)
  }
  return value
}

export function createTargetFingerprint(targetEnv, baseUrl) {
  return sha256(`${requireText(targetEnv, 'targetEnv')}\n${requireText(baseUrl, 'baseUrl')}`, 'hex')
}

export function createFixtureFingerprint(fixtureText) {
  return sha256(requireText(fixtureText, 'fixture text'), 'hex')
}

export function validateSmokeProof(proofValue, expected) {
  const proof = requireObject(proofValue, 'smoke proof')
  const expectation = requireObject(expected, 'smoke proof expectation')
  if (proof.schemaVersion !== SUMMARY_SCHEMA_VERSION) {
    throw new Error('smoke proof schema version is unsupported')
  }
  if (proof.profile !== 'smoke' || proof.prerequisiteSmokeRunId !== null) {
    throw new Error('smoke proof must come from a smoke profile')
  }
  if (proof.targetEnv !== expectation.targetEnv) {
    throw new Error('smoke proof target environment does not match')
  }
  if (proof.runId !== expectation.smokeRunId) {
    throw new Error('smoke proof run ID does not match')
  }
  if (proof.commitSha !== expectation.commitSha) {
    throw new Error('smoke proof commit does not match')
  }
  const expectedTarget = createTargetFingerprint(expectation.targetEnv, expectation.baseUrl)
  if (!SHA256_PATTERN.test(proof.targetFingerprint)
      || proof.targetFingerprint !== expectedTarget) {
    throw new Error('smoke proof target does not match')
  }
  const expectedFixture = createFixtureFingerprint(expectation.fixtureText)
  if (!SHA256_PATTERN.test(proof.fixtureSha256)
      || proof.fixtureSha256 !== expectedFixture) {
    throw new Error('smoke proof fixture does not match')
  }
  if (proof.thresholdsPassed !== true) {
    throw new Error('smoke proof thresholds did not pass')
  }
  if (!Array.isArray(proof.scenarioNames)
      || new Set(proof.scenarioNames).size !== proof.scenarioNames.length
      || !Array.isArray(expectation.scenarioNames)
      || expectation.scenarioNames.some((name) => !proof.scenarioNames.includes(name))) {
    throw new Error('smoke proof does not cover every baseline scenario')
  }
  const limits = requireObject(proof.limits, 'smoke proof limits')
  if (limits.maxVus !== 1 || limits.durationSeconds !== 1 || limits.arrivalRate !== 1) {
    throw new Error('smoke proof does not use fixed smoke limits')
  }
  return proof
}
