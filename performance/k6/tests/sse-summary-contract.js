import { check } from 'k6'

import { renderSafeSseSummary, validateSseSmokeProof } from '../sse/summary.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

const SHA = 'a'.repeat(40)
const FIXTURE_SHA = 'b'.repeat(64)
const TARGET_FINGERPRINT = 'c'.repeat(64)

const METADATA = {
  targetEnv: 'local',
  profile: 'smoke',
  runId: 'safe-run',
  prerequisiteSmokeRunId: null,
  commitSha: SHA,
  harnessCommitSha: SHA,
  targetFingerprint: TARGET_FINGERPRINT,
  fixtureSha256: FIXTURE_SHA,
  endpointKinds: ['notification-consumer'],
  limits: {
    connections: 1,
    connectionsPerAccount: 1,
    holdDurationSeconds: 5,
    slowClientDelaySeconds: 1,
  },
  accessToken: 'forbidden-token',
  cursor: 'forbidden-cursor',
  storeId: 'forbidden-store-301',
}

const SUMMARY_INPUT = {
  metrics: {
    'sse_first_event{phase:measured,profile:smoke,audience:consumer,endpoint_kind:notification-consumer}': {
      type: 'trend',
      values: { avg: 12.5, min: 10, med: 12, max: 15, 'p(50)': 12, 'p(95)': 14, 'p(99)': 15 },
      thresholds: { 'p(95)<2000': { ok: true } },
    },
    'sse_connections_successful{phase:measured,profile:smoke,audience:consumer,endpoint_kind:notification-consumer}': {
      type: 'counter',
      values: { count: 1, rate: 1 },
    },
    'sse_recovery_successful{phase:measured,profile:smoke,audience:consumer,endpoint_kind:notification-consumer}': {
      type: 'counter',
      values: { count: 0, rate: 0 },
    },
    'sse_unexpected_4xx{phase:measured,profile:smoke,audience:consumer,endpoint_kind:notification-consumer}': {
      type: 'counter',
      values: { count: 0, rate: 0 },
    },
    'dropped_iterations{phase:measured,profile:smoke}': {
      type: 'counter',
      values: { count: 0, rate: 0 },
      thresholds: { 'count==0': { ok: true } },
    },
    'leaked_metric{phase:measured,profile:smoke,endpoint_kind:notification-consumer}': {
      type: 'counter',
      values: { count: 999301, rate: 999301 },
    },
  },
}

function message(action) {
  try {
    action()
    return null
  } catch (error) {
    return error.message
  }
}

export default function () {
  const rendered = renderSafeSseSummary(SUMMARY_INPUT, METADATA)
  const parsed = JSON.parse(rendered.json)
  const combined = `${rendered.stdout}\n${rendered.json}\n${rendered.markdown}`

  const proof = JSON.parse(rendered.json)
  const validated = validateSseSmokeProof(proof, {
    targetEnv: 'local',
    commitSha: SHA,
    harnessCommitSha: SHA,
    targetFingerprint: TARGET_FINGERPRINT,
    fixtureSha256: FIXTURE_SHA,
    endpointKinds: ['notification-consumer'],
  })

  check(null, {
    'summary keeps only approved run evidence': () =>
      parsed.schemaVersion === 'miriyum-k6-sse-summary-v1'
      && parsed.targetEnv === 'local'
      && parsed.profile === 'smoke'
      && parsed.runId === 'safe-run'
      && parsed.commitSha === SHA
      && parsed.harnessCommitSha === SHA
      && parsed.targetFingerprint === TARGET_FINGERPRINT
      && parsed.fixtureSha256 === FIXTURE_SHA
      && parsed.endpointKinds.join(',') === 'notification-consumer'
      && parsed.thresholdsPassed === true,
    'summary keeps allowlisted numeric stream aggregates': () =>
      parsed.metrics['notification-consumer'].firstEvent.p95 === 14
      && parsed.metrics['notification-consumer'].successfulConnections.count === 1
      && parsed.metrics['notification-consumer'].recoverySuccessful.count === 0
      && parsed.metrics['notification-consumer'].unexpected4xx.count === 0,
    'summary keeps bounded run inputs and dropped iterations': () =>
      parsed.limits.connections === 1
      && parsed.limits.holdDurationSeconds === 5
      && parsed.runMetrics.droppedIterations.count === 0,
    'summary omits unknown metadata metrics and identifier sentinels': () =>
      !combined.includes('forbidden-token')
      && !combined.includes('forbidden-cursor')
      && !combined.includes('forbidden-store-301')
      && !combined.includes('leaked_metric')
      && !combined.includes('999301'),
    'matching smoke proof returns only its safe run ID': () =>
      validated.runId === 'safe-run'
      && JSON.stringify(Object.keys(validated).sort()) === JSON.stringify(['runId']),
    'failed smoke thresholds reject proof reuse': () =>
      message(() => validateSseSmokeProof({ ...proof, thresholdsPassed: false }, {
        targetEnv: 'local', commitSha: SHA, harnessCommitSha: SHA,
        targetFingerprint: TARGET_FINGERPRINT, fixtureSha256: FIXTURE_SHA,
        endpointKinds: ['notification-consumer'],
      })) === 'SSE smoke proof thresholds did not pass',
    'cross-target or cross-fixture smoke proof is rejected': () =>
      message(() => validateSseSmokeProof({ ...proof, fixtureSha256: 'd'.repeat(64) }, {
        targetEnv: 'local', commitSha: SHA, harnessCommitSha: SHA,
        targetFingerprint: TARGET_FINGERPRINT, fixtureSha256: FIXTURE_SHA,
        endpointKinds: ['notification-consumer'],
      })) === 'SSE smoke proof fixture does not match',
    'proof must cover every requested endpoint kind': () =>
      message(() => validateSseSmokeProof(proof, {
        targetEnv: 'local', commitSha: SHA, harnessCommitSha: SHA,
        targetFingerprint: TARGET_FINGERPRINT, fixtureSha256: FIXTURE_SHA,
        endpointKinds: ['notification-consumer', 'waiting-consumer'],
      })) === 'SSE smoke proof endpoint coverage does not match',
  })
}
