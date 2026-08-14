import { classifyStatus, parseEnvelope, recordClassification } from '../lib/contracts.js'

function validateSearchData(response) {
  const data = parseEnvelope(response).data
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('store search data must be an object')
  }
  for (const field of [
    'items',
    'normalizedCondition',
    'warnings',
    'ruleVersion',
    'vocabularyVersion',
    'rankingRuleVersion',
    'nextCursor',
  ]) {
    if (!Object.prototype.hasOwnProperty.call(data, field)) {
      throw new Error(`store search data must contain ${field}`)
    }
  }
  if (!Array.isArray(data.items) || !Array.isArray(data.warnings)) {
    throw new Error('store search items and warnings must be arrays')
  }
}

export function runStoreSearch({ client, baseUrl, search, tags = {} }) {
  if (search === null || typeof search !== 'object') {
    throw new Error('store search fixture is required')
  }
  if (typeof search.input !== 'string' || search.input.trim() === '') {
    throw new Error('store search input is required')
  }

  const requestTagSet = { phase: 'measured', ...tags, request: 'storeSearch' }
  const response = client.get(
    `${baseUrl}/api/v1/stores?searchInput=${encodeURIComponent(search.input)}`,
    { headers: { Accept: 'application/json' }, tags: requestTagSet, redirects: 0 },
  )
  const classification = recordClassification(
    classifyStatus(response.status, [429]),
    requestTagSet,
  )
  if (classification === 'success') validateSearchData(response)

  return { status: response.status, classification }
}
