import { check } from 'k6'
import { validateAlternativeResponse, validateIntegratedSearchResponse, validateSearchLlmFixture } from '../search-llm/contracts.js'
import { buildSearchLlmScenarios, runSearchLlmCase } from '../search-llm/scenarios.js'

export const options = { thresholds: { checks: ['rate==1'] } }
function throws(action) { try { action(); return false } catch (_) { return true } }

const item = {
  storeId: 2, storeName: 'synthetic', menuId: 3, menuName: 'candidate', unitPrice: 9000,
  availableOnlineQuantity: 5, secondaryCategoryMatchCount: 1, distanceMeters: null,
  coordinates: null, reasonCodes: ['IN_STOCK'], alternativeScore: 80,
  rankingReason: 'LLM_CONCEPT', scoreBreakdown: { llmConcept: 50, secondaryCategory: 10, priceSimilarity: 20 },
}
const envelope = (mode, items) => ({ code: 'SUCCESS', message: 'ok', data: {
  sourceStoreId: 1, sourceMenuId: 1, quantity: 1, startAt: '2026-08-22T12:00:00+09:00',
  serviceEndAt: '2026-08-22T13:00:00+09:00', timeZoneId: 'Asia/Seoul', mode, items,
} })

export default function () {
  const calls = []
  const client = {
    get: (url, params) => { calls.push({ method: 'GET', url, params }); return { status: 200, json: () => ({ code: 'SUCCESS', message: 'ok', data: { items: [], normalizedCondition: {}, warnings: [], ruleVersion: 'v1', vocabularyVersion: 'v1', rankingRuleVersion: null, nextCursor: null } }) } },
    post: (url, body, params) => { calls.push({ method: 'POST', url, body, params }); return { status: 200, json: () => envelope('SAME_STORE', [{ ...item, storeId: 1 }]) } },
  }
  check(null, {
    'integrated search requires the cursor response contract': () => !throws(() => validateIntegratedSearchResponse({ code: 'SUCCESS', message: 'ok', data: { items: [], normalizedCondition: {}, warnings: [], ruleVersion: 'v1', vocabularyVersion: 'v1', rankingRuleVersion: null, nextCursor: null } })),
    'same-store response contains only source store candidates': () => !throws(() => validateAlternativeResponse(envelope('SAME_STORE', [{ ...item, storeId: 1 }]), { sourceStoreId: 1, sourceUnitPrice: 10000 })),
    'nearby candidates stay within three kilometres': () => !throws(() => validateAlternativeResponse(envelope('NEARBY_STORE', [{ ...item, distanceMeters: 3000, coordinates: { latitude: 37.5, longitude: 127 } }]), { sourceStoreId: 1, sourceUnitPrice: 10000 })),
    'nearby distance overflow is rejected': () => throws(() => validateAlternativeResponse(envelope('NEARBY_STORE', [{ ...item, distanceMeters: 3000.1, coordinates: { latitude: 37.5, longitude: 127 } }]), { sourceStoreId: 1, sourceUnitPrice: 10000 })),
    'price outside plus or minus twenty percent is rejected': () => throws(() => validateAlternativeResponse(envelope('SAME_STORE', [{ ...item, storeId: 1, unitPrice: 12001 }]), { sourceStoreId: 1, sourceUnitPrice: 10000 })),
    'score must equal its breakdown': () => throws(() => validateAlternativeResponse(envelope('SAME_STORE', [{ ...item, storeId: 1, alternativeScore: 79 }]), { sourceStoreId: 1, sourceUnitPrice: 10000 })),
    'five scenarios remain independently scheduled': () => Object.keys(buildSearchLlmScenarios({ scenarios: ['exact', 'natural-language', 'same-store', 'nearby-store', 'fallback'], limits: { maxVus: 2, arrivalRate: 1, durationSeconds: 30 }, budget: { plannedCalls: 4 } }, { cases: [{ scenario: 'exact' }, { scenario: 'natural-language' }, { scenario: 'same-store' }, { scenario: 'nearby-store' }, { scenario: 'fallback' }] })).length === 5,
    'exact search uses the public integrated search endpoint': () => {
      calls.length = 0
      const result = runSearchLlmCase({ client, baseUrl: 'https://staging.example', fixtureCase: { scenario: 'exact', searchInput: 'synthetic exact', minimumItems: 0 } })
      return result.completed && calls.length === 1 && calls[0].method === 'GET' && calls[0].url.includes('/api/v1/stores?searchInput=')
    },
    'alternative search sends only the approved request body': () => {
      calls.length = 0
      const request = { quantity: 1, serviceDate: '2026-08-23', startTime: '12:00', partySize: 2 }
      const result = runSearchLlmCase({ client, baseUrl: 'https://staging.example', fixtureCase: { scenario: 'same-store', storeId: 1, menuId: 1, sourceUnitPrice: 10000, request } })
      return result.completed && calls.length === 1 && calls[0].method === 'POST' && calls[0].body === JSON.stringify(request)
    },
    'fallback fixtures distinguish disabled and timeout controls': () => !throws(() => validateSearchLlmFixture({ cases: [
      { alias: 'disabled', scenario: 'fallback', fallbackMode: 'disabled', fallbackTarget: 'search', searchInput: 'synthetic fallback', minimumItems: 1 },
      { alias: 'timeout', scenario: 'fallback', fallbackMode: 'timeout', fallbackTarget: 'alternative', storeId: 1, menuId: 1, sourceUnitPrice: 10000, request: { quantity: 1 } },
    ] })),
    'fallback fixture without a control mode is rejected': () => throws(() => validateSearchLlmFixture({ cases: [
      { alias: 'missing', scenario: 'fallback', searchInput: 'synthetic fallback' },
    ] })),
    'search result below the fixture minimum is rejected': () => throws(() => runSearchLlmCase({ client, baseUrl: 'https://staging.example', fixtureCase: { scenario: 'fallback', fallbackMode: 'disabled', fallbackTarget: 'search', searchInput: 'synthetic fallback', minimumItems: 1 } })),
  })
}
