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
    sse_unexpected_400: {
      type: 'counter', values: { count: 1, rate: 1 },
    },
    sse_unexpected_401: {
      type: 'counter', values: { count: 2, rate: 2 },
    },
    sse_unexpected_403: {
      type: 'counter', values: { count: 3, rate: 3 },
    },
    sse_unexpected_other_4xx: {
      type: 'counter', values: { count: 4, rate: 4 },
    },
    sse_unexpected_403_code_common_010: {
      type: 'counter', values: { count: 5, rate: 5 },
    },
    sse_unexpected_403_code_auth_006: {
      type: 'counter', values: { count: 6, rate: 6 },
    },
    sse_unexpected_403_code_auth_009: {
      type: 'counter', values: { count: 7, rate: 7 },
    },
    sse_unexpected_403_code_auth_010: {
      type: 'counter', values: { count: 8, rate: 8 },
    },
    sse_unexpected_403_code_auth_011: {
      type: 'counter', values: { count: 9, rate: 9 },
    },
    sse_unexpected_403_code_auth_012: {
      type: 'counter', values: { count: 10, rate: 10 },
    },
    sse_unexpected_403_code_other_or_missing: {
      type: 'counter', values: { count: 11, rate: 11 },
    },
    sse_unexpected_code_auth_006: {
      type: 'counter', values: { count: 99, rate: 99 },
    },
    owned_http_baseline: {
      type: 'trend',
      values: { avg: 12, min: 8, med: 11, max: 19, 'p(50)': 11, 'p(95)': 18, 'p(99)': 19 },
    },
    owned_http_duration: {
      type: 'trend',
      values: { avg: 14, min: 9, med: 12, max: 102.23, 'p(50)': 12, 'p(95)': 20, 'p(99)': 80 },
    },
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

const SLOW_METADATA = {
  ...METADATA,
  profile: 'slow-client',
  runId: 'safe-slow-run',
  endpointKinds: ['waiting-store-operator'],
  limits: {
    connections: 2,
    connectionsPerAccount: 2,
    holdDurationSeconds: 100,
    slowClientDelaySeconds: 40,
    slowClientMaxCleanupSeconds: 60,
    companionMinLifetimeSeconds: 85,
  },
}

const RECONNECT_METADATA = {
  ...METADATA,
  profile: 'reconnect',
  runId: 'safe-reconnect-run',
  limits: {
    ...METADATA.limits,
    reconnectSettleSeconds: 6,
  },
}

const SLOW_SUMMARY_INPUT = {
  metrics: {
    'sse_slow_cleanup_duration{phase:measured,profile:slow-client,endpoint_kind:waiting-store-operator}': {
      type: 'trend',
      values: { avg: 52000, min: 52000, med: 52000, max: 52000, 'p(50)': 52000, 'p(95)': 52000, 'p(99)': 52000 },
      thresholds: { 'max<=60000': { ok: true } },
    },
    'sse_companion_lifetime{phase:measured,profile:slow-client,endpoint_kind:waiting-store-operator}': {
      type: 'trend',
      values: { avg: 90000, min: 90000, med: 90000, max: 90000, 'p(50)': 90000, 'p(95)': 90000, 'p(99)': 90000 },
      thresholds: { 'min>=85000': { ok: true } },
    },
  },
}

const RECOVERY_METADATA = {
  ...METADATA,
  profile: 'recovery',
  runId: 'safe-recovery-run',
  endpointKinds: ['waiting-store-operator'],
  limits: {
    connections: 1,
    connectionsPerAccount: 1,
    holdDurationSeconds: 30,
    slowClientDelaySeconds: 1,
    recoveryArmDelaySeconds: 15,
    recoveryMaxSeconds: 6,
  },
}

const RECOVERY_SUMMARY_INPUT = {
  metrics: {
    'sse_recovery_duration{phase:measured,profile:recovery,endpoint_kind:waiting-store-operator}': {
      type: 'trend',
      values: { avg: 2100, min: 2100, med: 2100, max: 2100, 'p(50)': 2100, 'p(95)': 2100, 'p(99)': 2100 },
      thresholds: { 'max<=6000': { ok: true } },
    },
    'sse_recovery_http_verified{phase:measured,profile:recovery,traffic:owned-http}': {
      type: 'counter', values: { count: 1, rate: 1 }, thresholds: { 'count==1': { ok: true } },
    },
    'sse_recovery_cleanup_successful{phase:cleanup,profile:recovery,traffic:cleanup}': {
      type: 'counter', values: { count: 1, rate: 1 }, thresholds: { 'count==1': { ok: true } },
    },
    'sse_recovery_trigger_list_failures{phase:measured,profile:recovery,traffic:trigger}': {
      type: 'counter', values: { count: 1, rate: 1 },
    },
    'sse_recovery_trigger_fixture_failures{phase:measured,profile:recovery,traffic:trigger}': {
      type: 'counter', values: { count: 2, rate: 2 },
    },
    'sse_recovery_trigger_call_failures{phase:measured,profile:recovery,traffic:trigger}': {
      type: 'counter', values: { count: 3, rate: 3 },
    },
    'sse_recovery_trigger_response_failures{phase:measured,profile:recovery,traffic:trigger}': {
      type: 'counter', values: { count: 4, rate: 4 },
    },
  },
}

const STAGING_RECOVERY_METADATA = {
  ...RECOVERY_METADATA,
  targetEnv: 'staging',
  runId: 'safe-staging-recovery-run',
  limits: {
    ...RECOVERY_METADATA.limits,
    recoveryArmDelaySeconds: null,
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
  const reconnectParsed = JSON.parse(
    renderSafeSseSummary(SUMMARY_INPUT, RECONNECT_METADATA).json,
  )

  const proof = JSON.parse(rendered.json)
  const validated = validateSseSmokeProof(proof, {
    targetEnv: 'local',
    commitSha: SHA,
    harnessCommitSha: SHA,
    targetFingerprint: TARGET_FINGERPRINT,
    fixtureSha256: FIXTURE_SHA,
    endpointKinds: ['notification-consumer'],
  })
  let slowParsed = null
  const slowError = message(() => {
    slowParsed = JSON.parse(renderSafeSseSummary(SLOW_SUMMARY_INPUT, SLOW_METADATA).json)
  })
  const recoveryRendered = renderSafeSseSummary(RECOVERY_SUMMARY_INPUT, RECOVERY_METADATA)
  const recoveryParsed = JSON.parse(recoveryRendered.json)
  let stagingRecoveryParsed = null
  const stagingRecoveryError = message(() => {
    stagingRecoveryParsed = JSON.parse(
      renderSafeSseSummary(RECOVERY_SUMMARY_INPUT, STAGING_RECOVERY_METADATA).json,
    )
  })
  const stagingFixedDelayError = message(() => renderSafeSseSummary(
    RECOVERY_SUMMARY_INPUT,
    {
      ...STAGING_RECOVERY_METADATA,
      limits: { ...STAGING_RECOVERY_METADATA.limits, recoveryArmDelaySeconds: 15 },
    },
  ))
  const localNullDelayError = message(() => renderSafeSseSummary(
    RECOVERY_SUMMARY_INPUT,
    {
      ...RECOVERY_METADATA,
      limits: { ...RECOVERY_METADATA.limits, recoveryArmDelaySeconds: null },
    },
  ))

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
    'summary preserves only fixed unexpected 4xx status bucket totals': () =>
      parsed.runMetrics.unexpected400.count === 1
      && parsed.runMetrics.unexpected401.count === 2
      && parsed.runMetrics.unexpected403.count === 3
      && parsed.runMetrics.unexpectedOther4xx.count === 4
      && rendered.stdout.includes('unexpected 4xx status buckets: 400=1, 401=2, 403=3, other=4'),
    'summary preserves only bounded unexpected 403-by-code totals': () =>
      parsed.runMetrics.unexpected403CodeCommon010.count === 5
      && parsed.runMetrics.unexpected403CodeAuth006.count === 6
      && parsed.runMetrics.unexpected403CodeAuth009.count === 7
      && parsed.runMetrics.unexpected403CodeAuth010.count === 8
      && parsed.runMetrics.unexpected403CodeAuth011.count === 9
      && parsed.runMetrics.unexpected403CodeAuth012.count === 10
      && parsed.runMetrics.unexpected403CodeOtherOrMissing.count === 11
      && parsed.runMetrics.unexpectedCodeAuth006 === undefined
      && rendered.stdout.includes('unexpected 403 error code buckets: COMMON_010=5, AUTH_006=6, AUTH_009=7, AUTH_010=8, AUTH_011=9, AUTH_012=10, other-or-missing=11'),
    'reconnect summary preserves the bounded registry settle window': () =>
      reconnectParsed.limits.reconnectSettleSeconds === 6,
    'summary keeps safe run-wide owned HTTP timing aggregates': () =>
      parsed.runMetrics.ownedHttpBaseline.max === 19
      && parsed.runMetrics.ownedHttpBaseline.p95 === 18
      && parsed.runMetrics.ownedHttpDuration.max === 102.23
      && parsed.runMetrics.ownedHttpDuration.p95 === 20
      && rendered.stdout.includes('owned HTTP baseline p95/max ms: 18 / 19')
      && rendered.stdout.includes('owned HTTP measured p95/max ms: 20 / 102.23'),
    'slow summary preserves only bounded cleanup and companion evidence': () =>
      slowError === null
      && slowParsed.limits.slowClientDelaySeconds === 40
      && slowParsed.limits.slowClientMaxCleanupSeconds === 60
      && slowParsed.limits.companionMinLifetimeSeconds === 85
      && slowParsed.metrics['waiting-store-operator'].slowCleanupDuration.max === 52000
      && slowParsed.metrics['waiting-store-operator'].companionLifetime.min === 90000,
    'recovery summary preserves only bounded aggregate evidence': () =>
      recoveryParsed.limits.recoveryArmDelaySeconds === 15
      && recoveryParsed.limits.recoveryMaxSeconds === 6
      && recoveryParsed.metrics['waiting-store-operator'].recoveryDuration.max === 2100
      && recoveryParsed.runMetrics.recoveryHttpVerified.count === 1
      && recoveryParsed.runMetrics.recoveryCleanupSuccessful.count === 1
      && recoveryRendered.stdout.includes('recovery max ms: 2100')
      && recoveryRendered.stdout.includes('HTTP verified: 1')
      && recoveryRendered.stdout.includes('cleanup successful: 1'),
    'staging rendezvous recovery summary preserves a null fixed arm delay': () =>
      stagingRecoveryError === null
      && stagingRecoveryParsed.targetEnv === 'staging'
      && stagingRecoveryParsed.limits.recoveryArmDelaySeconds === null
      && stagingRecoveryParsed.limits.recoveryMaxSeconds === 6,
    'recovery summary rejects arm delay metadata from the wrong environment contract': () =>
      stagingFixedDelayError === 'recoveryArmDelaySeconds is invalid'
      && localNullDelayError === 'recoveryArmDelaySeconds is invalid',
    'recovery summary preserves only fixed trigger failure aggregates': () =>
      recoveryParsed.runMetrics.recoveryTriggerListFailures.count === 1
      && recoveryParsed.runMetrics.recoveryTriggerFixtureFailures.count === 2
      && recoveryParsed.runMetrics.recoveryTriggerCallFailures.count === 3
      && recoveryParsed.runMetrics.recoveryTriggerResponseFailures.count === 4
      && recoveryRendered.stdout.includes('trigger list failures: 1')
      && recoveryRendered.stdout.includes('trigger fixture failures: 2')
      && recoveryRendered.stdout.includes('trigger call failures: 3')
      && recoveryRendered.stdout.includes('trigger response failures: 4'),
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
