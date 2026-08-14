import crypto from 'k6/crypto'

function requireText(name, value) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${name} is required`)
  }
  return value.trim()
}

export function jsonHeaders(extra = {}) {
  return {
    ...extra,
    'Content-Type': 'application/json',
    Accept: 'application/json',
  }
}

export function bearerHeaders(accessToken, extra = {}) {
  return {
    ...jsonHeaders(extra),
    Authorization: `Bearer ${requireText('accessToken', accessToken)}`,
  }
}

export function originHeaders(origin, extra = {}) {
  return {
    ...jsonHeaders(extra),
    Origin: requireText('origin', origin),
  }
}

export function deterministicUuid(seed) {
  const hex = crypto.sha256(requireText('seed', seed), 'hex')
  const variant = ((Number.parseInt(hex[16], 16) & 0x3) | 0x8).toString(16)
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    `4${hex.slice(13, 16)}`,
    `${variant}${hex.slice(17, 20)}`,
    hex.slice(20, 32),
  ].join('-')
}
