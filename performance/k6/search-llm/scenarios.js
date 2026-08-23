import { validateAlternativeResponse, validateIntegratedSearchResponse } from './contracts.js'

const MAX_CASES = Object.freeze({ exact: 10, 'natural-language': 60, 'same-store': 30, 'nearby-store': 30, fallback: 20 })

export function buildSearchLlmScenarios(config, fixture) {
  const result = {}
  config.scenarios.forEach((name, index) => {
    const selectedCases = fixture.cases.filter((entry) => entry.scenario === name)
    if (name === 'fallback'
      && selectedCases.some((entry) => entry.fallbackMode !== config.fallbackMode)) {
      throw new Error('fallback fixture does not match the approved runtime mode')
    }
    const count = selectedCases.length
    if (count < 1 || count > MAX_CASES[name]) throw new Error(`${name} fixture count is outside its safety bound`)
    if (count * 2 > config.limits.durationSeconds) throw new Error(`${name} fixture count exceeds its bounded duration margin`)
    result[name] = {
      executor: 'shared-iterations', exec: 'executeSearchLlmCase', vus: 1, iterations: count,
      maxDuration: `${config.limits.durationSeconds}s`,
      startTime: `${index * config.limits.durationSeconds}s`,
      tags: { phase: 'measured', scenario: name },
    }
  })
  const potentialCalls = config.scenarios
    .filter((name) => name !== 'exact')
    .reduce((sum, name) => sum + fixture.cases.filter((entry) => entry.scenario === name).length, 0)
  if (potentialCalls > config.budget.plannedCalls) throw new Error('planned call budget does not cover fixture cases')
  return result
}

export function runSearchLlmCase({ client, baseUrl, fixtureCase }) {
  const tags = { phase: 'measured', scenario: fixtureCase.scenario }
  if (fixtureCase.scenario === 'exact' || fixtureCase.scenario === 'natural-language'
    || (fixtureCase.scenario === 'fallback' && fixtureCase.fallbackTarget === 'search')) {
    const response = client.get(`${baseUrl}/api/v1/stores?searchInput=${encodeURIComponent(fixtureCase.searchInput)}&size=20`, {
      tags: { ...tags, name: 'integrated-store-search' }, redirects: 0,
    })
    if (response.status !== 200) return { completed: false, status: response.status }
    const result = validateIntegratedSearchResponse(response.json())
    if (result.itemCount < fixtureCase.minimumItems) throw new Error('search result is below fixture minimum')
    return { completed: true, status: 200 }
  }
  const response = client.post(
    `${baseUrl}/api/v1/stores/${fixtureCase.storeId}/menus/${fixtureCase.menuId}/alternative-searches`,
    JSON.stringify(fixtureCase.request),
    { headers: { 'Content-Type': 'application/json' }, tags: { ...tags, name: 'menu-alternative-search' }, redirects: 0 },
  )
  if (response.status !== 200) return { completed: false, status: response.status }
  const result = validateAlternativeResponse(response.json(), {
    sourceStoreId: fixtureCase.storeId,
    sourceUnitPrice: fixtureCase.sourceUnitPrice,
  })
  if (fixtureCase.scenario === 'same-store' && result.mode !== 'SAME_STORE') throw new Error('same-store fixture did not return SAME_STORE')
  if (fixtureCase.scenario === 'nearby-store' && result.mode !== 'NEARBY_STORE') throw new Error('nearby-store fixture did not return NEARBY_STORE')
  return { completed: true, status: 200, mode: result.mode }
}
