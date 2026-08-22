const SUMMARY_SCHEMA_VERSION = 'miriyum-k6-summary-v1'
const SHA256_PATTERN = /^[0-9a-f]{64}$/

function requireObject(value, name) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`)
  }
  return value
}

function requireScenarioNames(value, name) {
  if (!Array.isArray(value)
    || value.length === 0
    || value.some((scenario) => typeof scenario !== 'string' || scenario === '')
    || new Set(value).size !== value.length) {
    throw new Error(`${name} must contain unique scenario names`)
  }
  return value
}

export function validateCapacityProof(proofValue, expectedValue) {
  const proof = requireObject(proofValue, 'capacity proof')
  const expected = requireObject(expectedValue, 'capacity proof expectation')
  if (proof.schemaVersion !== SUMMARY_SCHEMA_VERSION
    || proof.targetEnv !== 'staging'
    || proof.profile !== 'staging-capacity') {
    throw new Error('capacity proof contract is unsupported')
  }
  if (proof.thresholdsPassed !== true) {
    throw new Error('capacity proof thresholds did not pass')
  }
  if (!SHA256_PATTERN.test(proof.targetFingerprint)
    || proof.targetFingerprint !== expected.targetFingerprint) {
    throw new Error('capacity proof target does not match')
  }
  if (!SHA256_PATTERN.test(proof.fixtureSha256)
    || proof.fixtureSha256 !== expected.fixtureSha256) {
    throw new Error('capacity proof fixture does not match')
  }
  if (proof.commitSha !== expected.commitSha) {
    throw new Error('capacity proof backend commit does not match')
  }
  if (proof.harnessCommitSha !== expected.harnessCommitSha) {
    throw new Error('capacity proof harness commit does not match')
  }
  const capacity = requireObject(proof.capacity, 'capacity proof metadata')
  if (capacity.stageNumber !== expected.previousStageNumber) {
    throw new Error('capacity proof must come from the immediately previous stage')
  }
  if (!Number.isSafeInteger(capacity.targetRps) || capacity.targetRps <= 0) {
    throw new Error('capacity proof target RPS is invalid')
  }
  const proofScenarioNames = requireScenarioNames(proof.scenarioNames, 'capacity proof scenarios')
  const expectedScenarioNames = requireScenarioNames(
    expected.scenarioNames,
    'capacity proof expected scenarios',
  )
  if (proofScenarioNames.length !== expectedScenarioNames.length
    || expectedScenarioNames.some((scenario) => !proofScenarioNames.includes(scenario))) {
    throw new Error('capacity proof scenario composition does not match')
  }
  const metrics = requireObject(proof.metrics, 'capacity proof metrics')
  const actualRps = expectedScenarioNames.reduce((total, scenario) => {
    const scenarioMetrics = requireObject(metrics[scenario], `capacity proof ${scenario} metrics`)
    const httpRequests = requireObject(
      scenarioMetrics.httpRequests,
      `capacity proof ${scenario} HTTP requests`,
    )
    if (typeof httpRequests.rate !== 'number'
      || !Number.isFinite(httpRequests.rate)
      || httpRequests.rate < 0) {
      throw new Error(`capacity proof ${scenario} actual RPS is invalid`)
    }
    return total + httpRequests.rate
  }, 0)
  if (actualRps < capacity.targetRps) {
    throw new Error('capacity proof actual RPS is below its planned target')
  }
  return proof
}
