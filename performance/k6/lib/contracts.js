function requireObject(value, name) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`)
  }
  return value
}

export function parseEnvelope(response) {
  requireObject(response, 'response')
  if (typeof response.body !== 'string') {
    throw new Error('response body must be JSON text')
  }

  let envelope
  try {
    envelope = JSON.parse(response.body)
  } catch (_) {
    throw new Error('response body must contain valid JSON')
  }
  requireObject(envelope, 'response envelope')
  for (const field of ['code', 'message', 'data']) {
    if (!Object.prototype.hasOwnProperty.call(envelope, field)) {
      throw new Error(`response envelope must contain ${field}`)
    }
  }
  if (typeof envelope.code !== 'string' || typeof envelope.message !== 'string') {
    throw new Error('response envelope code and message must be strings')
  }
  return {
    code: envelope.code,
    message: envelope.message,
    data: envelope.data,
  }
}

export function classifyStatus(status, expected4xx = []) {
  if (!Number.isInteger(status)) throw new Error('HTTP status must be an integer')
  if (status >= 200 && status < 300) return 'success'
  if (status >= 400 && status < 500) {
    return expected4xx.includes(status) ? 'expected_4xx' : 'unexpected_4xx'
  }
  if (status >= 500 && status < 600) return 'server_5xx'
  return 'unexpected_status'
}
