import { check } from 'k6'
import http from 'k6/http'

import { loadRecoveryConfig } from './config.js'
import {
  EXPECTED_SUCCESSFUL_LOGINS,
  renderRecoverySummary,
  verifyRecoveryRateLimit,
} from './lib/recovery-rate-limit.js'

const config = loadRecoveryConfig(__ENV)

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
  },
  userAgent: `miriyum-k6-recovery/${config.harnessCommitSha.slice(0, 12)}`,
}

export default function () {
  let result
  try {
    result = verifyRecoveryRateLimit({
      client: http,
      baseUrl: config.baseUrl,
      allowedOrigin: config.baseUrl,
      account: config.account,
    })
  } catch (_) {
    check(null, { 'rate-limit recovery contract is restored': () => false })
    return
  }
  check(result, {
    'rate-limit recovery contract is restored': (value) =>
      value.completed === true
      && value.successfulLogins === EXPECTED_SUCCESSFUL_LOGINS
      && value.finalStatus === 429,
  })
}

export function handleSummary(data) {
  const rendered = renderRecoverySummary(data, {
    targetEnv: config.targetEnv,
    runId: config.runId,
    commitSha: config.commitSha,
    harnessCommitSha: config.harnessCommitSha,
  })
  return {
    stdout: rendered.stdout,
    [`/results/${config.runId}.recovery.json`]: rendered.json,
    [`/results/${config.runId}.recovery.md`]: rendered.markdown,
  }
}
