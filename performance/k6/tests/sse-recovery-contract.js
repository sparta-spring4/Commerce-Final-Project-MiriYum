import { check } from 'k6'

import { awaitRecoveryArmedMarker, runSseRecovery } from '../sse/recovery.js'

export const options = { thresholds: { checks: ['rate==1'] } }

function errorMessage(action) {
  try { action(); return null } catch (error) { return error.message }
}

export default function () {
  const order = []
  const metrics = []
  let now = 1000
  const result = runSseRecovery({
    armWindowSeconds: 15,
    maxRecoverySeconds: 6,
    arm: () => order.push('delay:15'),
    ready: () => order.push('ready'),
    now: () => now,
    openStream: (behavior) => {
      order.push('open')
      if (behavior.timeoutSeconds !== 21) {
        throw new Error('recovery transport timeout does not cover arm and measurement windows')
      }
      behavior.onFirstValidEvent()
      now = 3100
      behavior.onRecoveryValidEvent()
      return { completed: true, validEvents: 2 }
    },
    trigger: () => { order.push('trigger'); return { waitingTeamId: '901', version: 5 } },
    verify: () => { order.push('verify'); return true },
    cleanup: () => { order.push('cleanup'); return true },
    metrics: {
      duration: (value) => metrics.push(['duration', value]),
      httpVerified: (value) => metrics.push(['http', value]),
      cleanupSuccessful: (value) => metrics.push(['cleanup', value]),
    },
  })

  const failureOrder = []
  const failure = errorMessage(() => runSseRecovery({
    armWindowSeconds: 15,
    maxRecoverySeconds: 6,
    arm: () => {},
    ready: () => {},
    now: () => 1000,
    openStream: (behavior) => {
      behavior.onFirstValidEvent()
      return { completed: false, validEvents: 1 }
    },
    trigger: () => ({ waitingTeamId: '902', version: 2 }),
    verify: () => true,
    cleanup: () => { failureOrder.push('cleanup'); return true },
  }))

  const rendezvousNow = 1787469000000
  const rendezvousDelays = []
  const rendezvousRequests = []
  const rendezvousResult = awaitRecoveryArmedMarker({
    client: {
      get: (url, options) => {
        rendezvousRequests.push([url, options])
        return {
          status: 200,
          json: () => [{
            user: { login: 'github-actions[bot]' },
            created_at: new Date(rendezvousNow).toISOString(),
            body: 'SSE_RECOVERY_ARMED run_id=32623080912 rendezvous_id=323e4567-e89b-12d3-a456-426614174000 execute_at_epoch=1787469030',
          }],
        }
      },
    },
    repository: 'sparta-spring4/Commerce-Final-Project-MiriYum',
    issue: 357,
    runId: '32623080912',
    rendezvousId: '323e4567-e89b-12d3-a456-426614174000',
    maxWaitSeconds: 60,
    delay: (seconds) => rendezvousDelays.push(seconds),
    now: () => rendezvousNow,
  })

  let timedOutTriggerCalled = false
  const timeoutFailure = errorMessage(() => runSseRecovery({
    armWindowSeconds: 60,
    maxRecoverySeconds: 6,
    arm: () => { throw new Error('SSE recovery armed marker timed out') },
    ready: () => {},
    now: () => rendezvousNow,
    openStream: (behavior) => {
      behavior.onFirstValidEvent()
      return { completed: false, validEvents: 1 }
    },
    trigger: () => { timedOutTriggerCalled = true; return {} },
    verify: () => true,
    cleanup: () => true,
  }))

  const wrongAuthorFailure = errorMessage(() => awaitRecoveryArmedMarker({
    client: {
      get: () => ({
        status: 200,
        json: () => [{
          user: { login: 'another-member' },
          created_at: new Date(rendezvousNow).toISOString(),
          body: 'SSE_RECOVERY_ARMED run_id=32623080912 rendezvous_id=323e4567-e89b-12d3-a456-426614174000 execute_at_epoch=1787469030',
        }],
      }),
    },
    repository: 'sparta-spring4/Commerce-Final-Project-MiriYum',
    issue: 357,
    runId: '32623080912',
    rendezvousId: '323e4567-e89b-12d3-a456-426614174000',
    maxWaitSeconds: 3,
    delay: () => {},
    now: () => rendezvousNow,
  }))
  const staleEpochFailure = errorMessage(() => awaitRecoveryArmedMarker({
    client: {
      get: () => ({
        status: 200,
        json: () => [{
          user: { login: 'github-actions[bot]' },
          created_at: new Date(rendezvousNow).toISOString(),
          body: 'SSE_RECOVERY_ARMED run_id=32623080912 rendezvous_id=323e4567-e89b-12d3-a456-426614174000 execute_at_epoch=1787468999',
        }],
      }),
    },
    repository: 'sparta-spring4/Commerce-Final-Project-MiriYum',
    issue: 357,
    runId: '32623080912',
    rendezvousId: '323e4567-e89b-12d3-a456-426614174000',
    maxWaitSeconds: 3,
    delay: () => {},
    now: () => rendezvousNow,
  }))

  check(null, {
    'recovery opens the stream before arming and measures the second changed frame': () =>
      order.join(',') === 'open,ready,delay:15,trigger,verify,cleanup'
      && result.recoveryMilliseconds === 2100
      && JSON.stringify(metrics) === JSON.stringify([
        ['duration', 2100], ['http', 1], ['cleanup', 1],
      ]),
    'recovery cleans up a successful mutation even when the second frame is missing': () =>
      failure === 'SSE recovery did not observe the corrected event'
      && failureOrder.join(',') === 'cleanup',
    'armed marker is exact, bot-authored, bounded, and unauthenticated': () =>
      rendezvousResult.executeAtEpoch === 1787469030
      && rendezvousDelays.length === 1
      && rendezvousDelays[0] === 33
      && rendezvousRequests.length === 1
      && rendezvousRequests[0][0].startsWith('https://api.github.com/repos/sparta-spring4/Commerce-Final-Project-MiriYum/issues/357/comments?')
      && rendezvousRequests[0][1].headers.Authorization === undefined,
    'rendezvous timeout fails before fixture-consuming mutation': () =>
      timeoutFailure === 'SSE recovery armed marker timed out'
      && timedOutTriggerCalled === false,
    'non-bot marker cannot arm recovery': () =>
      wrongAuthorFailure === 'SSE recovery armed marker timed out',
    'stale armed epoch fails closed': () =>
      staleEpochFailure === 'SSE recovery armed epoch is outside the bounded window',
  })
}
