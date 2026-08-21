function requireFunction(name, value) {
  if (typeof value !== 'function') throw new Error(`${name} is required`)
  return value
}

export function runSseRecovery({
  armDelaySeconds,
  maxRecoverySeconds,
  delay,
  ready,
  now = Date.now,
  openStream,
  trigger,
  verify,
  cleanup,
  metrics = {},
}) {
  if (!Number.isInteger(armDelaySeconds) || armDelaySeconds < 1 || armDelaySeconds > 60) {
    throw new Error('recovery arm delay is invalid')
  }
  if (!Number.isInteger(maxRecoverySeconds) || maxRecoverySeconds < 1 || maxRecoverySeconds > 60) {
    throw new Error('recovery maximum seconds is invalid')
  }
  const wait = requireFunction('recovery delay', delay)
  const announce = requireFunction('recovery readiness reporter', ready)
  const clock = requireFunction('recovery clock', now)
  const open = requireFunction('recovery stream opener', openStream)
  const mutate = requireFunction('recovery trigger', trigger)
  const verifyHttp = requireFunction('recovery HTTP verifier', verify)
  const cleanupMutation = requireFunction('recovery cleanup', cleanup)

  let mutation = null
  let triggeredAt = null
  let recoveredAt = null
  let result = null
  try {
    result = open({
      mode: 'recovery',
      timeoutSeconds: armDelaySeconds + maxRecoverySeconds,
      minimumValidEvents: 2,
      onFirstValidEvent: () => {
        announce()
        wait(armDelaySeconds)
        triggeredAt = clock()
        mutation = mutate()
      },
      onRecoveryValidEvent: () => { recoveredAt = clock() },
    })
    if (result?.completed !== true
      || result?.validEvents !== 2
      || mutation === null
      || triggeredAt === null
      || recoveredAt === null
      || recoveredAt < triggeredAt) {
      throw new Error('SSE recovery did not observe the corrected event')
    }
    const recoveryMilliseconds = recoveredAt - triggeredAt
    if (verifyHttp(mutation) !== true) throw new Error('SSE recovery HTTP verification failed')
    if (typeof metrics.duration === 'function') metrics.duration(recoveryMilliseconds)
    if (typeof metrics.httpVerified === 'function') metrics.httpVerified(1)
    return Object.freeze({ recoveryMilliseconds })
  } finally {
    if (mutation !== null) {
      if (cleanupMutation(mutation) !== true) throw new Error('SSE recovery cleanup failed')
      if (typeof metrics.cleanupSuccessful === 'function') metrics.cleanupSuccessful(1)
    }
  }
}
