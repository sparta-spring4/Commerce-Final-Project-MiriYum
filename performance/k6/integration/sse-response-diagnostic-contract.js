import { check } from 'k6'
import http from 'k6/http'
import sse from 'k6/x/sse'

import { openChangedStream } from '../sse/session.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

export default function () {
  const diagnostics = []
  let diagnosticCallCount = 0
  const result = openChangedStream({
    transport: sse,
    diagnosticClient: {
      get(url, params) {
        diagnosticCallCount += 1
        return http.get(url, params)
      },
    },
    url: 'http://sse-error-backend:8080/api/v1/consumers/me/notification-events',
    accessToken: 'runtime-contract-token',
    endpointKind: 'notification-consumer',
    connectionStage: 'reconnect',
    behavior: { mode: 'reconnect', timeoutSeconds: 2 },
    metrics: {
      connectionResult(classification, _tags, diagnostic) {
        diagnostics.push({ classification, diagnostic })
      },
    },
    tags: {
      phase: 'measured',
      profile: 'reconnect',
      audience: 'consumer',
      endpoint_kind: 'notification-consumer',
      traffic: 'sse-stream',
    },
  })

  check(null, {
    'pinned xk6-sse 403 uses one bounded HTTP diagnostic request': () =>
      result.classification === 'unexpected_client_error'
      && diagnosticCallCount === 1,
    'runtime diagnostic retains only the allowlisted 403 code bucket': () =>
      diagnostics.length === 1
      && diagnostics[0].diagnostic.statusBucket === '403'
      && diagnostics[0].diagnostic.errorCodeBucket === 'COMMON_010'
      && !JSON.stringify(diagnostics).includes('runtime-contract-message'),
  })
}
