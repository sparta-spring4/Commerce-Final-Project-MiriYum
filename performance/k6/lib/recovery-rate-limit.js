import { loginConsumer, runAuthRefresh } from '../scenarios/auth-refresh.js'

export const EXPECTED_SUCCESSFUL_LOGINS = 5

function thresholdsPassed(data) {
  const threshold = data?.metrics?.checks?.thresholds?.['rate==1']
  return threshold?.ok === true
}

export function renderRecoverySummary(data, metadata) {
  const verified = thresholdsPassed(data)
  const summary = {
    schemaVersion: 'miriyum-k6-recovery-v1',
    targetEnv: metadata.targetEnv,
    runId: metadata.runId,
    commitSha: metadata.commitSha,
    harnessCommitSha: metadata.harnessCommitSha,
    successfulLogins: verified ? EXPECTED_SUCCESSFUL_LOGINS : null,
    finalStatus: verified ? 429 : null,
    thresholdsPassed: verified,
  }
  const json = `${JSON.stringify(summary, null, 2)}\n`
  const markdown = [
    '# k6 rate-limit recovery summary',
    '',
    `- targetEnv: ${summary.targetEnv}`,
    `- runId: ${summary.runId}`,
    `- commitSha: ${summary.commitSha}`,
    `- harnessCommitSha: ${summary.harnessCommitSha}`,
    `- successfulLogins: ${summary.successfulLogins ?? '-'}`,
    `- finalStatus: ${summary.finalStatus ?? '-'}`,
    `- thresholdsPassed: ${summary.thresholdsPassed}`,
    '',
  ].join('\n')
  return { stdout: markdown, json, markdown }
}

export function verifyRecoveryRateLimit({ client, baseUrl, allowedOrigin, account }) {
  for (let attempt = 0; attempt < EXPECTED_SUCCESSFUL_LOGINS; attempt += 1) {
    loginConsumer({
      client,
      baseUrl,
      account,
      tags: { phase: 'recovery', recovery_step: 'successful_login' },
    })
  }

  const finalProbe = runAuthRefresh({
    client,
    baseUrl,
    allowedOrigin,
    account,
    tags: { phase: 'recovery', recovery_step: 'final_probe' },
  })
  if (finalProbe.loginStatus !== 429
    || finalProbe.refreshStatus !== null
    || finalProbe.classification !== 'expected_4xx'
    || finalProbe.completed !== false) {
    throw new Error('rate-limit recovery probe did not end with the expected HTTP 429')
  }

  return Object.freeze({
    successfulLogins: EXPECTED_SUCCESSFUL_LOGINS,
    finalStatus: 429,
    completed: true,
  })
}
