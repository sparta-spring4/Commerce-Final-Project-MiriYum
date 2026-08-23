function requireFunction(name, value) {
  if (typeof value !== 'function') throw new Error(`${name} is required`)
  return value
}

const GITHUB_API_ORIGIN = 'https://api.github.com'
const ARMED_AUTHOR = 'github-actions[bot]'
const POLL_INTERVAL_SECONDS = 3
const MUTATION_OFFSET_SECONDS = 3
const MIN_ARMED_LEAD_SECONDS = 2
const MAX_ARMED_LEAD_SECONDS = 60

function requireText(name, value) {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`${name} is required`)
  return value.trim()
}

function parsedComments(response) {
  if (response?.status !== 200 || typeof response.json !== 'function') {
    throw new Error('SSE recovery armed marker lookup failed')
  }
  const comments = response.json()
  if (!Array.isArray(comments)) throw new Error('SSE recovery armed marker response is invalid')
  return comments
}

export function awaitRecoveryArmedMarker({
  client,
  repository,
  issue,
  runId,
  rendezvousId,
  maxWaitSeconds,
  delay,
  now = Date.now,
  diagnostic = () => {},
}) {
  if (client === null || typeof client?.get !== 'function') {
    throw new Error('SSE recovery rendezvous client is required')
  }
  const selectedRepository = requireText('SSE recovery repository', repository)
  if (selectedRepository !== 'sparta-spring4/Commerce-Final-Project-MiriYum') {
    throw new Error('SSE recovery rendezvous repository is not approved')
  }
  if (!Number.isInteger(issue) || issue <= 0 || issue > 9999999999) {
    throw new Error('SSE recovery rendezvous Issue is invalid')
  }
  const selectedRunId = requireText('SSE recovery run ID', runId)
  const selectedRendezvousId = requireText('SSE recovery rendezvous ID', rendezvousId)
  if (!/^[1-9][0-9]{0,19}$/.test(selectedRunId)
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(selectedRendezvousId)) {
    throw new Error('SSE recovery rendezvous scope is invalid')
  }
  if (!Number.isInteger(maxWaitSeconds) || maxWaitSeconds < 1 || maxWaitSeconds > 60) {
    throw new Error('SSE recovery rendezvous wait is invalid')
  }
  const wait = requireFunction('SSE recovery rendezvous delay', delay)
  const clock = requireFunction('SSE recovery rendezvous clock', now)
  const reportDiagnostic = requireFunction('SSE recovery diagnostic reporter', diagnostic)
  const startedAt = clock()
  const since = new Date(Math.floor(startedAt / 1000) * 1000).toISOString()
  const markerPrefix = `SSE_RECOVERY_ARMED run_id=${selectedRunId} rendezvous_id=${selectedRendezvousId} execute_at_epoch=`
  const url = `${GITHUB_API_ORIGIN}/repos/${selectedRepository}/issues/${issue}/comments?since=${encodeURIComponent(since)}&per_page=100`

  for (let elapsed = 0; elapsed < maxWaitSeconds; elapsed += POLL_INTERVAL_SECONDS) {
    const comments = parsedComments(client.get(url, {
      headers: { Accept: 'application/vnd.github+json' },
      redirects: 0,
      tags: { phase: 'control', profile: 'recovery', traffic: 'rendezvous' },
    }))
    const candidate = comments.find((comment) => comment?.user?.login === ARMED_AUTHOR
      && typeof comment.body === 'string'
      && comment.body.startsWith(markerPrefix))
    if (candidate !== undefined) {
      const match = new RegExp(`^${markerPrefix}([0-9]{10})$`).exec(candidate.body)
      const createdAt = Date.parse(candidate.created_at)
      if (match === null || !Number.isFinite(createdAt) || createdAt < Math.floor(startedAt / 1000) * 1000) {
        throw new Error('SSE recovery armed marker is invalid')
      }
      const executeAtEpoch = Number(match[1])
      const observedAt = clock()
      const leadSeconds = executeAtEpoch - Math.floor(observedAt / 1000)
      if (leadSeconds < MIN_ARMED_LEAD_SECONDS || leadSeconds > MAX_ARMED_LEAD_SECONDS) {
        throw new Error('SSE recovery armed epoch is outside the bounded window')
      }
      reportDiagnostic('armed-marker-observed', Math.floor(observedAt / 1000))
      wait((executeAtEpoch * 1000 + MUTATION_OFFSET_SECONDS * 1000 - observedAt) / 1000)
      return Object.freeze({ executeAtEpoch })
    }
    wait(POLL_INTERVAL_SECONDS)
  }
  throw new Error('SSE recovery armed marker timed out')
}

export function runSseRecovery({
  armWindowSeconds,
  maxRecoverySeconds,
  arm,
  ready,
  now = Date.now,
  openStream,
  trigger,
  verify,
  cleanup,
  metrics = {},
  diagnostic = () => {},
}) {
  if (!Number.isInteger(armWindowSeconds) || armWindowSeconds < 1 || armWindowSeconds > 180) {
    throw new Error('recovery arm window is invalid')
  }
  if (!Number.isInteger(maxRecoverySeconds) || maxRecoverySeconds < 1 || maxRecoverySeconds > 60) {
    throw new Error('recovery maximum seconds is invalid')
  }
  const awaitArm = requireFunction('recovery arm', arm)
  const announce = requireFunction('recovery readiness reporter', ready)
  const clock = requireFunction('recovery clock', now)
  const open = requireFunction('recovery stream opener', openStream)
  const mutate = requireFunction('recovery trigger', trigger)
  const verifyHttp = requireFunction('recovery HTTP verifier', verify)
  const cleanupMutation = requireFunction('recovery cleanup', cleanup)
  const reportDiagnostic = requireFunction('recovery diagnostic reporter', diagnostic)

  let mutation = null
  let triggeredAt = null
  let recoveredAt = null
  let result = null
  try {
    try {
      result = open({
        mode: 'recovery',
        timeoutSeconds: armWindowSeconds + maxRecoverySeconds,
        minimumValidEvents: 2,
        onFirstValidEvent: () => {
          reportDiagnostic('initial-event-observed', Math.floor(clock() / 1000))
          announce()
          awaitArm()
          triggeredAt = clock()
          reportDiagnostic('mutation-started', Math.floor(triggeredAt / 1000))
          mutation = mutate()
        },
        onRecoveryValidEvent: () => {
          recoveredAt = clock()
          reportDiagnostic('recovery-event-observed', Math.floor(recoveredAt / 1000))
        },
      })
    } finally {
      reportDiagnostic('stream-finished', Math.floor(clock() / 1000))
    }
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
