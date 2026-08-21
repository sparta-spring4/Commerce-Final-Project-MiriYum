import { check } from 'k6'

import { runSseRecovery } from '../sse/recovery.js'

export const options = { thresholds: { checks: ['rate==1'] } }

function errorMessage(action) {
  try { action(); return null } catch (error) { return error.message }
}

export default function () {
  const order = []
  const metrics = []
  let now = 1000
  const result = runSseRecovery({
    armDelaySeconds: 15,
    maxRecoverySeconds: 6,
    delay: (seconds) => order.push(`delay:${seconds}`),
    ready: () => order.push('ready'),
    now: () => now,
    openStream: (behavior) => {
      order.push('open')
      if (behavior.timeoutSeconds !== 6) throw new Error('recovery timeout is not bounded')
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
    armDelaySeconds: 15,
    maxRecoverySeconds: 6,
    delay: () => {},
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

  check(null, {
    'recovery arms before opening and measures the second changed frame': () =>
      order.join(',') === 'ready,delay:15,open,trigger,verify,cleanup'
      && result.recoveryMilliseconds === 2100
      && JSON.stringify(metrics) === JSON.stringify([
        ['duration', 2100], ['http', 1], ['cleanup', 1],
      ]),
    'recovery cleans up a successful mutation even when the second frame is missing': () =>
      failure === 'SSE recovery did not observe the corrected event'
      && failureOrder.join(',') === 'cleanup',
  })
}
