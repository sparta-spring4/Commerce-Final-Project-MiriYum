import { check } from 'k6'

import { renderSafeSummary } from '../lib/summary.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

const SUMMARY_INPUT = {
  metrics: {
    'checks{phase:measured,scenario:notificationHistory}': {
      type: 'rate',
      contains: 'default',
      values: { rate: 1, passes: 3, fails: 0 },
      thresholds: { 'rate==1': { ok: true } },
    },
    'http_req_duration{phase:measured,scenario:notificationHistory}': {
      type: 'trend',
      contains: 'time',
      values: { avg: 11.2, 'p(50)': 10.1, 'p(95)': 20.2, 'p(99)': 25.3, max: 30.4 },
    },
    'http_reqs{phase:measured,scenario:notificationHistory}': {
      type: 'counter',
      contains: 'default',
      values: { count: 60, rate: 12.5 },
    },
    'dropped_iterations{phase:measured,scenario:notificationHistory}': {
      type: 'counter',
      contains: 'default',
      values: { count: 2, rate: 0.2 },
    },
    http_req_duration: {
      type: 'trend',
      contains: 'time',
      values: { avg: 9999, 'p(95)': 9999 },
    },
    leaked_response_body: {
      type: 'counter',
      contains: 'default',
      values: { count: 1, rate: 1 },
    },
  },
}

export default function () {
  const rendered = renderSafeSummary(SUMMARY_INPUT, {
    targetEnv: 'local',
    profile: 'smoke',
    runId: 'safe-run',
    prerequisiteSmokeRunId: 'local-smoke-approved',
    commitSha: '0123456789abcdef0123456789abcdef01234567',
    harnessCommitSha: 'fedcba9876543210fedcba9876543210fedcba98',
    scenarioNames: ['notificationHistory'],
    targetFingerprint: 'a'.repeat(64),
    fixtureSha256: 'b'.repeat(64),
    limits: { maxVus: 1, durationSeconds: 1, arrivalRate: 1 },
    capacity: { stageNumber: 2, targetRps: 25 },
    forbiddenProbe: 'Bearer secret-token cursor-secret response-body',
  })
  const combined = `${rendered.stdout}\n${rendered.json}\n${rendered.markdown}`
  const parsed = JSON.parse(rendered.json)

  check(null, {
    'summary keeps approved run metadata': () =>
      parsed.runId === 'safe-run'
      && parsed.targetEnv === 'local'
      && parsed.prerequisiteSmokeRunId === 'local-smoke-approved'
      && parsed.schemaVersion === 'miriyum-k6-summary-v1'
      && parsed.commitSha === '0123456789abcdef0123456789abcdef01234567'
      && parsed.harnessCommitSha === 'fedcba9876543210fedcba9876543210fedcba98'
      && parsed.thresholdsPassed === true
      && parsed.targetFingerprint === 'a'.repeat(64)
      && parsed.fixtureSha256 === 'b'.repeat(64)
      && parsed.scenarioNames.join(',') === 'notificationHistory',
    'summary keeps capacity stage and planned target separately from measured RPS': () =>
      parsed.capacity.stageNumber === 2
      && parsed.capacity.targetRps === 25
      && parsed.metrics.notificationHistory.httpRequests.rate === 12.5,
    'summary keeps measured scenario percentiles': () =>
      parsed.metrics.notificationHistory.httpReqDuration.p95 === 20.2,
    'summary discloses dropped configured arrivals': () =>
      parsed.metrics.notificationHistory.droppedIterations.count === 2,
    'summary excludes untagged preparation aggregate': () => !combined.includes('9999'),
    'summary excludes unknown metrics': () => !combined.includes('leaked_response_body'),
    'summary excludes forbidden metadata and credentials': () =>
      !combined.includes('secret-token')
      && !combined.includes('cursor-secret')
      && !combined.includes('response-body'),
  })
}
