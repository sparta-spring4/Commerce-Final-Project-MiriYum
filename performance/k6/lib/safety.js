const VALID_TARGET_ENVS = new Set(['local', 'staging'])
const PRODUCTION_HOSTS = new Set([
  'api.miriyum.com',
  'miriyum.com',
  'www.miriyum.com',
])
const LOCAL_HOSTS = new Set([
  'loadtest-proxy',
  'localhost',
  '127.0.0.1',
  '::1',
])
// Staging stays fail-closed until an approved hostname is added in a reviewed repository change.
const TRUSTED_STAGING_HOSTS = new Set([])

export function assertSafeTarget(targetEnv, baseUrl, allowedHosts) {
  if (!VALID_TARGET_ENVS.has(targetEnv)) {
    throw new Error('TARGET_ENV must be local or staging')
  }
  if (!Array.isArray(allowedHosts) || allowedHosts.length === 0) {
    throw new Error('ALLOWED_HOSTS must contain at least one host')
  }

  const match = /^(https?):\/\/([^/?#]+)(?:[/?#]|$)/i.exec(baseUrl)
  if (match === null || match[2].includes('@')) {
    throw new Error('BASE_URL must be an absolute HTTP URL')
  }

  const protocol = `${match[1].toLowerCase()}:`
  const authority = match[2]
  const hostname = authority.startsWith('[')
    ? authority.slice(1, authority.indexOf(']')).toLowerCase()
    : authority.split(':', 1)[0].toLowerCase()
  const normalizedAllowedHosts = allowedHosts.map((host) => host.trim().toLowerCase())
  if (hostname === '') throw new Error('BASE_URL must contain a host')
  if (PRODUCTION_HOSTS.has(hostname)) {
    throw new Error('production target is forbidden')
  }
  if (!normalizedAllowedHosts.includes(hostname)) {
    throw new Error('target host is not allowlisted')
  }
  if (protocol !== 'https:') {
    throw new Error(`${targetEnv} target must use HTTPS`)
  }
  if (targetEnv === 'local' && !LOCAL_HOSTS.has(hostname)) {
    throw new Error('local target must use the dedicated proxy or a loopback host')
  }
  if (targetEnv === 'staging' && !TRUSTED_STAGING_HOSTS.has(hostname)) {
    throw new Error('staging target host is not configured in the trusted repository allowlist')
  }
}

export function parsePositiveInt(name, rawValue, maximum) {
  const value = Number(rawValue)
  if (!Number.isInteger(value) || value <= 0 || value > maximum) {
    throw new Error(`${name} must be an integer between 1 and ${maximum}`)
  }
  return value
}
